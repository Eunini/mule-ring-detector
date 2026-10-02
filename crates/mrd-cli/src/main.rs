//! `mrd` — mule-ring detector command line.

mod serve;

use anyhow::{bail, Context, Result};
use clap::{Parser, Subcommand};
use mrd_core::alert::Alert;
use mrd_core::io::{prepare_sorted, TxnReader};
use mrd_core::model::gbdt::GbdtParams;
use mrd_core::model::lr::LrParams;
use mrd_core::model::ModelFile;
use mrd_core::types::parse_ts;
use mrd_core::{Engine, EngineConfig};
use mrd_eval::pipeline::{self, Baselines, EvalOptions, TrainOptions};
use mrd_eval::split::Splits;
use mrd_eval::synth::{self, SynthConfig};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

#[derive(Parser)]
#[command(
    name = "mrd",
    version,
    about = "Streaming money-mule / laundering-ring detection"
)]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Sort a raw IBM AMLworld CSV by time, add row ids and write time-based splits.
    Prepare {
        #[arg(long)]
        input: PathBuf,
        #[arg(long)]
        output: PathBuf,
        /// Where to write the split boundaries (JSON).
        #[arg(long)]
        splits: PathBuf,
        #[arg(long, default_value_t = 0.6)]
        train_fraction: f64,
        #[arg(long, default_value_t = 0.8)]
        val_fraction: f64,
    },
    /// Generate a synthetic AMLSim-style dataset (transactions + patterns file).
    Synth {
        #[arg(long)]
        out: PathBuf,
        #[arg(long)]
        patterns_out: PathBuf,
        #[arg(long, default_value_t = 650)]
        accounts: usize,
        #[arg(long, default_value_t = 12)]
        days: u32,
        #[arg(long, default_value_t = 0.4)]
        rate: f64,
        #[arg(long, default_value_t = 6)]
        patterns_per_typology: usize,
        #[arg(long, default_value_t = 42)]
        seed: u64,
    },
    /// Replay train+validation, train models, pick thresholds on validation.
    Train {
        #[arg(long)]
        input: PathBuf,
        #[arg(long)]
        splits: PathBuf,
        #[arg(long, default_value = "models")]
        out_dir: PathBuf,
        /// Engine/detector configuration JSON (defaults if omitted).
        #[arg(long)]
        config: Option<PathBuf>,
        #[arg(long, default_value_t = 0.1)]
        neg_rate: f64,
        #[arg(long, default_value_t = 300)]
        trees: usize,
        #[arg(long, default_value_t = 4)]
        depth: usize,
    },
    /// Replay the full stream with the trained model and report test metrics.
    Eval {
        #[arg(long)]
        input: PathBuf,
        #[arg(long)]
        splits: PathBuf,
        #[arg(long, default_value = "models")]
        models_dir: PathBuf,
        #[arg(long)]
        patterns: Option<PathBuf>,
        #[arg(long, default_value = "reports")]
        out_dir: PathBuf,
        /// Optional second test window ending here (e.g. 2022-09-11T00:00).
        #[arg(long)]
        test_end: Option<String>,
    },
    /// Measure throughput, per-transaction latency and memory on a full replay.
    Bench {
        #[arg(long)]
        input: PathBuf,
        #[arg(long)]
        model: PathBuf,
        #[arg(long)]
        config: Option<PathBuf>,
        #[arg(long)]
        out: Option<PathBuf>,
        #[arg(long)]
        limit: Option<u64>,
    },
    /// Replay a sorted CSV as a stream, emitting alerts (stdout/file and/or HTTP push).
    Replay {
        #[arg(long)]
        input: PathBuf,
        #[arg(long)]
        model: PathBuf,
        #[arg(long)]
        config: Option<PathBuf>,
        /// Event-time acceleration (event seconds per wall second); 0 = as fast as possible.
        #[arg(long, default_value_t = 0.0)]
        speed: f64,
        /// Only emit alerts at or after this event time (state is still built from the start).
        #[arg(long)]
        emit_from: Option<String>,
        #[arg(long)]
        limit: Option<u64>,
        #[arg(long)]
        alerts_out: Option<PathBuf>,
        /// Case-service endpoint, e.g. http://localhost:8085/api/alerts
        #[arg(long)]
        push: Option<String>,
        #[arg(long, default_value = "engine")]
        push_user: String,
        /// Password for --push (or env MRD_PUSH_PASSWORD).
        #[arg(long, env = "MRD_PUSH_PASSWORD", hide_env_values = true)]
        push_password: Option<String>,
        #[arg(long, default_value_t = 50)]
        batch: usize,
        /// Stop after this many alerts.
        #[arg(long)]
        max_alerts: Option<usize>,
    },
    /// HTTP ingest API: POST /v1/transactions scores a transfer in real time.
    Serve {
        #[arg(long)]
        model: PathBuf,
        #[arg(long)]
        config: Option<PathBuf>,
        #[arg(long, default_value = "127.0.0.1:8090")]
        listen: String,
        #[arg(long)]
        push: Option<String>,
        #[arg(long, default_value = "engine")]
        push_user: String,
        #[arg(long, env = "MRD_PUSH_PASSWORD", hide_env_values = true)]
        push_password: Option<String>,
    },
}

fn load_config(p: &Option<PathBuf>) -> Result<EngineConfig> {
    match p {
        Some(p) => Ok(serde_json::from_str(&std::fs::read_to_string(p)?)?),
        None => Ok(EngineConfig::standard()),
    }
}

fn load_splits(p: &Path) -> Result<Splits> {
    serde_json::from_str(
        &std::fs::read_to_string(p).with_context(|| format!("read {}", p.display()))?,
    )
    .context("parse splits")
}

fn write_json(path: &Path, v: &impl serde::Serialize) -> Result<()> {
    if let Some(d) = path.parent() {
        std::fs::create_dir_all(d)?;
    }
    std::fs::write(path, serde_json::to_string_pretty(v)?)?;
    Ok(())
}

/// (peak RSS, current RSS) in MiB from /proc (Linux only).
pub fn memory_mib() -> (f64, f64) {
    let s = std::fs::read_to_string("/proc/self/status").unwrap_or_default();
    let get = |k: &str| {
        s.lines()
            .find(|l| l.starts_with(k))
            .and_then(|l| l.split_whitespace().nth(1))
            .and_then(|v| v.parse::<f64>().ok())
            .map_or(0.0, |kb| kb / 1024.0)
    };
    (get("VmHWM:"), get("VmRSS:"))
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    match cli.cmd {
        Cmd::Prepare {
            input,
            output,
            splits,
            train_fraction,
            val_fraction,
        } => {
            let n = prepare_sorted(&input, &output)?;
            let ts = pipeline::timestamps(&output)?;
            let s = Splits::from_fractions(&ts, train_fraction, val_fraction);
            write_json(&splits, &s)?;
            eprintln!("sorted {n} rows -> {}; {}", output.display(), s.describe());
        }
        Cmd::Synth {
            out,
            patterns_out,
            accounts,
            days,
            rate,
            patterns_per_typology,
            seed,
        } => {
            let cfg = SynthConfig {
                accounts,
                days,
                txns_per_account_day: rate,
                patterns_per_typology,
                seed,
                ..SynthConfig::default()
            };
            let o = synth::generate(&cfg);
            mrd_core::io::write_ibm(
                std::io::BufWriter::new(std::fs::File::create(&out)?),
                &o.txns,
            )?;
            synth::write_patterns(
                std::io::BufWriter::new(std::fs::File::create(&patterns_out)?),
                &o.patterns,
            )?;
            let pos = o.txns.iter().filter(|t| t.label == Some(true)).count();
            eprintln!(
                "wrote {} transactions ({pos} laundering, {} patterns)",
                o.txns.len(),
                o.patterns.len()
            );
        }
        Cmd::Train {
            input,
            splits,
            out_dir,
            config,
            neg_rate,
            trees,
            depth,
        } => {
            let engine = load_config(&config)?;
            let opt = TrainOptions {
                splits: load_splits(&splits)?,
                neg_rate,
                gbdt: GbdtParams {
                    n_trees: trees,
                    max_depth: depth,
                    ..GbdtParams::default()
                },
                lr: LrParams::default(),
                engine: engine.clone(),
                early_stop_every: 10,
            };
            let out = pipeline::train(&input, &opt)?;
            std::fs::create_dir_all(&out_dir)?;
            for (name, m) in &out.models {
                m.save(&out_dir.join(format!("{name}.json")))?;
            }
            write_json(&out_dir.join("baselines.json"), &out.baselines)?;
            write_json(&out_dir.join("engine_config.json"), &engine)?;
            write_json(&out_dir.join("train_report.json"), &out.report)?;
            println!("{}", serde_json::to_string_pretty(&out.report)?);
        }
        Cmd::Eval {
            input,
            splits,
            models_dir,
            patterns,
            out_dir,
            test_end,
        } => {
            let cfg_path = models_dir.join("engine_config.json");
            let engine = if cfg_path.exists() {
                load_config(&Some(cfg_path))?
            } else {
                EngineConfig::standard()
            };
            let mut models = Vec::new();
            for name in ["gbdt", "lr", "gbdt_txn_only"] {
                let p = models_dir.join(format!("{name}.json"));
                models.push((
                    name.to_string(),
                    ModelFile::load(&p).with_context(|| format!("load {}", p.display()))?,
                ));
            }
            let baselines: Baselines =
                serde_json::from_str(&std::fs::read_to_string(models_dir.join("baselines.json"))?)?;
            let test_end = match test_end {
                Some(s) => Some(parse_ts(&s).context("bad --test-end")?),
                None => None,
            };
            let opt = EvalOptions {
                splits: load_splits(&splits)?,
                engine,
                test_end,
            };
            let t0 = Instant::now();
            let reports =
                pipeline::evaluate(&input, &models, &baselines, patterns.as_deref(), &opt)?;
            std::fs::create_dir_all(&out_dir)?;
            write_json(&out_dir.join("eval.json"), &reports)?;
            let md = pipeline::to_markdown(&reports);
            std::fs::write(out_dir.join("eval.md"), &md)?;
            let mut csv = String::from("threshold,precision,recall,f1,alerts\n");
            for p in &reports[0].pr_curve {
                csv += &format!(
                    "{},{},{},{},{}\n",
                    p.threshold, p.precision, p.recall, p.f1, p.alerts
                );
            }
            std::fs::write(out_dir.join("pr_curve_test.csv"), csv)?;
            println!("{md}");
            eprintln!("evaluation took {:.1}s", t0.elapsed().as_secs_f64());
        }
        Cmd::Bench {
            input,
            model,
            config,
            out,
            limit,
        } => bench(&input, &model, &config, out.as_deref(), limit)?,
        Cmd::Replay {
            input,
            model,
            config,
            speed,
            emit_from,
            limit,
            alerts_out,
            push,
            push_user,
            push_password,
            batch,
            max_alerts,
        } => {
            let mut engine = Engine::new(load_config(&config)?, Some(ModelFile::load(&model)?));
            let emit_from = match emit_from {
                Some(s) => parse_ts(&s).context("bad --emit-from")?,
                None => 0,
            };
            let mut sink =
                AlertSink::new(alerts_out.as_deref(), push, push_user, push_password, batch)?;
            let started = Instant::now();
            let mut first_ts = None;
            let mut n = 0u64;
            for t in TxnReader::open(&input)? {
                let t = t?;
                if limit.is_some_and(|l| n >= l) {
                    break;
                }
                n += 1;
                if speed > 0.0 {
                    let f = *first_ts.get_or_insert(t.ts);
                    let due = Duration::from_secs_f64(f64::from(t.ts - f) * 60.0 / speed);
                    if let Some(wait) = due.checked_sub(started.elapsed()) {
                        std::thread::sleep(wait);
                    }
                }
                let s = engine.process(&t);
                if s.alert && t.ts >= emit_from {
                    let a = engine.build_alert(&t, &s);
                    sink.send(a)?;
                    if max_alerts.is_some_and(|m| sink.count >= m) {
                        break;
                    }
                }
            }
            sink.flush()?;
            eprintln!(
                "replayed {n} transfers in {:.1}s, {} alerts emitted",
                started.elapsed().as_secs_f64(),
                sink.count
            );
        }
        Cmd::Serve {
            model,
            config,
            listen,
            push,
            push_user,
            push_password,
        } => {
            let engine = Engine::new(load_config(&config)?, Some(ModelFile::load(&model)?));
            let sink = AlertSink::new(None, push, push_user, push_password, 1)?;
            serve::run(&listen, engine, sink)?;
        }
    }
    Ok(())
}

/// Delivers alerts to a JSONL file and/or the case-management service.
pub struct AlertSink {
    file: Option<std::io::BufWriter<std::fs::File>>,
    push: Option<(String, String)>,
    batch: usize,
    pending: Vec<Alert>,
    pub count: usize,
}

impl AlertSink {
    pub fn new(
        file: Option<&Path>,
        push: Option<String>,
        user: String,
        password: Option<String>,
        batch: usize,
    ) -> Result<Self> {
        let file = match file {
            Some(p) => Some(std::io::BufWriter::new(std::fs::File::create(p)?)),
            None => None,
        };
        let push = match push {
            Some(url) => {
                let Some(pw) = password else {
                    bail!("--push requires a password (--push-password or MRD_PUSH_PASSWORD)");
                };
                Some((
                    url,
                    format!("Basic {}", base64(format!("{user}:{pw}").as_bytes())),
                ))
            }
            None => None,
        };
        Ok(Self {
            file,
            push,
            batch: batch.max(1),
            pending: Vec::new(),
            count: 0,
        })
    }

    pub fn send(&mut self, a: Alert) -> Result<()> {
        self.count += 1;
        if let Some(f) = self.file.as_mut() {
            writeln!(f, "{}", serde_json::to_string(&a)?)?;
        }
        if self.push.is_some() {
            self.pending.push(a);
            if self.pending.len() >= self.batch {
                self.flush_push()?;
            }
        }
        Ok(())
    }

    fn flush_push(&mut self) -> Result<()> {
        if let (Some((url, auth)), false) = (&self.push, self.pending.is_empty()) {
            let mut attempt = 0;
            loop {
                let r = ureq::post(url)
                    .set("Authorization", auth)
                    .timeout(Duration::from_secs(10))
                    .send_json(serde_json::to_value(&self.pending)?);
                match r {
                    Ok(_) => break,
                    Err(e) if attempt < 3 => {
                        attempt += 1;
                        eprintln!("push failed ({e}); retry {attempt}");
                        std::thread::sleep(Duration::from_millis(500 * attempt));
                    }
                    Err(e) => return Err(e.into()),
                }
            }
            self.pending.clear();
        }
        Ok(())
    }

    pub fn flush(&mut self) -> Result<()> {
        self.flush_push()?;
        if let Some(f) = self.file.as_mut() {
            f.flush()?;
        }
        Ok(())
    }
}

fn base64(input: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    for c in input.chunks(3) {
        let b = [c[0], *c.get(1).unwrap_or(&0), *c.get(2).unwrap_or(&0)];
        let n = (u32::from(b[0]) << 16) | (u32::from(b[1]) << 8) | u32::from(b[2]);
        for i in 0..4 {
            if i <= c.len() {
                out.push(T[((n >> (18 - 6 * i)) & 63) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}

fn bench(
    input: &Path,
    model: &Path,
    config: &Option<PathBuf>,
    out: Option<&Path>,
    limit: Option<u64>,
) -> Result<()> {
    let (_, rss_start) = memory_mib();
    let mut engine = Engine::new(load_config(config)?, Some(ModelFile::load(model)?));
    let mut lat_ns: Vec<u32> = Vec::with_capacity(5_200_000);
    let mut alerts = 0u64;
    let mut max_state = 0usize;
    let started = Instant::now();
    let mut engine_time = Duration::ZERO;
    let mut n = 0u64;
    for t in TxnReader::open(input)? {
        let t = t?;
        if limit.is_some_and(|l| n >= l) {
            break;
        }
        n += 1;
        let t0 = Instant::now();
        let s = engine.process(&t);
        if s.alert {
            let a = engine.build_alert(&t, &s);
            alerts += 1;
            std::hint::black_box(a);
        }
        let d = t0.elapsed();
        engine_time += d;
        lat_ns.push(u32::try_from(d.as_nanos()).unwrap_or(u32::MAX));
        if n.is_multiple_of(100_000) {
            max_state = max_state.max(engine.stats().approx_bytes);
        }
    }
    let wall = started.elapsed();
    lat_ns.sort_unstable();
    let pct = |p: f64| f64::from(lat_ns[((lat_ns.len() as f64 - 1.0) * p) as usize]) / 1000.0;
    let (peak, rss) = memory_mib();
    let st = engine.stats();
    let report = serde_json::json!({
        "transactions": n,
        "alerts": alerts,
        "wall_seconds_including_csv_parse": wall.as_secs_f64(),
        "throughput_tps_end_to_end": n as f64 / wall.as_secs_f64(),
        "engine_seconds": engine_time.as_secs_f64(),
        "throughput_tps_engine_only": n as f64 / engine_time.as_secs_f64(),
        "latency_us": {"p50": pct(0.50), "p90": pct(0.90), "p99": pct(0.99), "p999": pct(0.999), "max": pct(1.0)},
        "memory_mib": {"rss_at_start": rss_start, "peak_rss": peak, "rss_at_end": rss,
            "note": "peak RSS includes the latency sample buffer (4 bytes per transaction)"},
        "graph_at_end": st,
        "max_estimated_state_mib": max_state as f64 / 1048576.0,
        "threads": 1,
    });
    println!("{}", serde_json::to_string_pretty(&report)?);
    if let Some(o) = out {
        write_json(o, &report)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn base64_matches_rfc4648() {
        assert_eq!(super::base64(b"engine:secret"), "ZW5naW5lOnNlY3JldA==");
        assert_eq!(super::base64(b"ab"), "YWI=");
        assert_eq!(super::base64(b"abc"), "YWJj");
    }
}
