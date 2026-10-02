//! L2-regularised logistic regression on standardised features, trained with full-batch Adam.

use super::{sigmoid, Dataset};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Lr {
    pub mean: Vec<f64>,
    pub std: Vec<f64>,
    pub weights: Vec<f64>,
    pub bias: f64,
}

#[derive(Debug, Clone, Copy)]
pub struct LrParams {
    pub epochs: usize,
    pub learning_rate: f64,
    pub l2: f64,
}

impl Default for LrParams {
    fn default() -> Self {
        Self {
            epochs: 300,
            learning_rate: 0.05,
            l2: 1e-4,
        }
    }
}

impl Lr {
    fn z(&self, x: &[f32], i: usize) -> f64 {
        (f64::from(x[i]) - self.mean[i]) / self.std[i]
    }

    pub fn predict(&self, x: &[f32]) -> f64 {
        let mut s = self.bias;
        for i in 0..self.weights.len() {
            s += self.weights[i] * self.z(x, i);
        }
        sigmoid(s)
    }

    pub fn contributions(&self, x: &[f32]) -> Vec<f64> {
        (0..self.weights.len())
            .map(|i| self.weights[i] * self.z(x, i))
            .collect()
    }

    pub fn train(d: &Dataset, p: LrParams) -> Lr {
        let n = d.len();
        let f = d.n_features;
        let wsum: f64 = d.w.iter().map(|&w| f64::from(w)).sum::<f64>().max(1e-12);
        let mut mean = vec![0.0; f];
        let mut var = vec![0.0; f];
        for r in 0..n {
            let w = f64::from(d.w[r]);
            for (j, m) in mean.iter_mut().enumerate() {
                *m += w * f64::from(d.row(r)[j]);
            }
        }
        for m in &mut mean {
            *m /= wsum;
        }
        for r in 0..n {
            let w = f64::from(d.w[r]);
            for j in 0..f {
                let dv = f64::from(d.row(r)[j]) - mean[j];
                var[j] += w * dv * dv;
            }
        }
        let std: Vec<f64> = var.iter().map(|v| (v / wsum).sqrt().max(1e-6)).collect();
        let pos: f64 = (0..n)
            .filter(|&r| d.y[r] == 1)
            .map(|r| f64::from(d.w[r]))
            .sum();
        let prior = (pos / wsum).clamp(1e-6, 1.0 - 1e-6);
        let mut m = Lr {
            mean,
            std,
            weights: vec![0.0; f],
            bias: (prior / (1.0 - prior)).ln(),
        };
        // Adam state: index f is the bias.
        let (b1, b2, eps) = (0.9, 0.999, 1e-8);
        let mut m1 = vec![0.0; f + 1];
        let mut m2 = vec![0.0; f + 1];
        let mut z = vec![0.0; f];
        for t in 1..=p.epochs {
            let mut grad = vec![0.0; f + 1];
            for r in 0..n {
                let row = d.row(r);
                let mut s = m.bias;
                for j in 0..f {
                    z[j] = (f64::from(row[j]) - m.mean[j]) / m.std[j];
                    s += m.weights[j] * z[j];
                }
                let g = (sigmoid(s) - f64::from(d.y[r])) * f64::from(d.w[r]);
                for j in 0..f {
                    grad[j] += g * z[j];
                }
                grad[f] += g;
            }
            for j in 0..=f {
                let mut gj = grad[j] / wsum;
                if j < f {
                    gj += p.l2 * m.weights[j];
                }
                m1[j] = b1 * m1[j] + (1.0 - b1) * gj;
                m2[j] = b2 * m2[j] + (1.0 - b2) * gj * gj;
                let mh = m1[j] / (1.0 - b1.powi(t as i32));
                let vh = m2[j] / (1.0 - b2.powi(t as i32));
                let step = p.learning_rate * mh / (vh.sqrt() + eps);
                if j < f {
                    m.weights[j] -= step;
                } else {
                    m.bias -= step;
                }
            }
        }
        m
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn separates_linearly_separable_data() {
        let mut d = Dataset::new(2);
        for i in 0..200 {
            let x = i as f32 / 100.0;
            d.push(&[x, 1.0], x > 1.0, 1.0);
        }
        let m = Lr::train(&d, LrParams::default());
        assert!(m.predict(&[1.8, 1.0]) > 0.9);
        assert!(m.predict(&[0.2, 1.0]) < 0.1);
        let c = m.contributions(&[1.8, 1.0]);
        assert!(c[0] > 0.0);
        assert!(c[1].abs() < 1e-6, "constant feature carries no signal");
    }
}
