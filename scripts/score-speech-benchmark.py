"""Score user-supplied local model runs; no downloads, inference APIs or invented timings.

Input JSONL: language, model, reference, hypothesis, latencyMs, optional ramBytes,
modelBytes, cpuMs, audioMs, device, offlineVerified, naturalnessScore.
Keep corpora out of source control. Explicitly running this command opts into output files.
"""
import argparse
import csv
import json
import statistics
import unicodedata
from pathlib import Path


def distance(a, b):
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        next_row = [i]
        for j, y in enumerate(b, 1):
            next_row.append(min(row[j] + 1, next_row[-1] + 1, row[j - 1] + (x != y)))
        row = next_row
    return row[-1]


def score(records):
    groups = {}
    for record in records:
        key = (record['model'], record['language'], record.get('device', 'unspecified'))
        groups.setdefault(key, []).append(record)
    results = []
    for (model, language, device), rows in groups.items():
        words = chars = word_errors = char_errors = 0
        for row in rows:
            reference = unicodedata.normalize('NFC', row['reference'])
            hypothesis = unicodedata.normalize('NFC', row['hypothesis'])
            words += len(reference.split())
            chars += len(reference)
            word_errors += distance(reference.split(), hypothesis.split())
            char_errors += distance(reference, hypothesis)
        result = dict(model=model, language=language, device=device, samples=len(rows),
                      wer=word_errors / words if words else None,
                      cer=char_errors / chars if chars else None,
                      normalization='NFC; case and punctuation retained',
                      offlineVerified=all(r.get('offlineVerified') is True for r in rows))
        for field in ('latencyMs', 'ramBytes', 'modelBytes', 'cpuMs', 'audioMs', 'naturalnessScore'):
            values = [r[field] for r in rows if isinstance(r.get(field), (int, float))]
            if any(v < 0 for v in values):
                raise ValueError(f'Negative measurement: {field}')
            result[field] = statistics.mean(values) if values else None
        results.append(result)
    return results


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    records = [json.loads(line) for line in args.input.read_text(encoding='utf-8').splitlines() if line.strip()]
    results = score(records)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'speech-summary.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    if results:
        with (args.output / 'speech-summary.csv').open('w', encoding='utf-8', newline='') as file:
            writer = csv.DictWriter(file, fieldnames=list(results[0]))
            writer.writeheader()
            writer.writerows(results)
    print(f'Scored {len(records)} supplied observations across {len(results)} model/language/device groups.')
