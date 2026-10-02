#!/usr/bin/env bash
# Download the IBM "Transactions for Anti Money Laundering (AMLworld)" HI-Small files
# (Altman et al., NeurIPS 2023 Datasets & Benchmarks; licence CDLA-Sharing-1.0).
# The canonical copy is on Kaggle (ealtman2019/ibm-transactions-for-anti-money-laundering-aml),
# which requires a login; this script uses a public Hugging Face mirror and verifies the
# transactions file against the SHA-256 recorded below.
set -euo pipefail

DEST="${1:-$(cd "$(dirname "$0")/.." && pwd)/data}"
BASE="https://huggingface.co/datasets/OsamaMIT/IBM-AML-HI-Small/resolve/main"
mkdir -p "$DEST"

fetch() {
  local name="$1"
  if [[ -s "$DEST/$name" ]]; then
    echo "have $name"
  else
    echo "downloading $name"
    curl -fL --retry 3 -o "$DEST/$name.part" "$BASE/$name"
    mv "$DEST/$name.part" "$DEST/$name"
  fi
}

fetch HI-Small_Trans.csv
fetch HI-Small_Patterns.txt

echo "verifying checksums"
(cd "$DEST" && sha256sum -c <<'SUMS'
b19d39f515523373f991b689c07e11e7b0b95c17a2c27a87d91584ae16c5b040  HI-Small_Trans.csv
2c546b5ce6009e73851f0139af053cf845f08bf92f3bc82fe1eb937dec2ef39b  HI-Small_Patterns.txt
SUMS
)
rows=$(($(wc -l < "$DEST/HI-Small_Trans.csv") - 1))
echo "HI-Small_Trans.csv: $rows transactions (expected 5078345)"
