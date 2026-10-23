#!/usr/bin/env python3
"""Compare anonymized, human-labeled Gemini v2/v3 predictions locally. No API or media access.

The evidence_source field is an operator declaration, NOT proof of a real API call.
Never use mocked fixture results to claim recognition accuracy.
"""
import argparse
import json
import pathlib
import re
import sys

BASELINE_VERSION = "gemini-independent-subjects-v2;rgb-jpeg512;video-3-distinct-frames;selected-uri-v1"
REVISED_VERSION = "gemini-photographic-intent-v3;rgb-jpeg512;video-3-distinct-frames;selected-uri-v1"


def compare(labels, baseline, revised):
    def require(condition):
        if not condition:
            raise ValueError("Invalid comparison contract; see docs/GEMINI_SUBJECT_INTENT.md")

    def exact(obj, keys):
        require(isinstance(obj, dict) and set(obj) == set(keys))

    exact(labels, ("configured_categories", "cases"))
    allowed = labels["configured_categories"]
    require(isinstance(allowed, list) and 1 <= len(allowed) <= 50)
    require(all(isinstance(c, str) and re.fullmatch(r"[a-zA-Z0-9_-]{1,160}", c) for c in allowed))
    require(len(set(allowed)) == len(allowed))

    def index(rows, prediction=False):
        require(isinstance(rows, list) and 1 <= len(rows) <= 10000)
        result = {}
        for row in rows:
            exact(row, ("id", "categories", "review_required", "status", "manually_corrected") if prediction
                  else ("id", "categories", "review_required"))
            key = row["id"]
            require(isinstance(key, str) and re.fullmatch(r"case-[0-9]{3,5}", key) and key not in result)
            cats = row["categories"]
            require(isinstance(cats, list) and len(cats) <= 50 and
                    all(isinstance(c, str) and c in allowed for c in cats))
            require(len(set(cats)) == len(cats) and type(row["review_required"]) is bool)
            require(len(cats) == 1 or row["review_required"])
            if prediction:
                require(row["status"] in ("PROPOSED", "NEEDS_REVIEW", "FAILED", "PENDING"))
                require(row["manually_corrected"] is False)
                if row["status"] in ("FAILED", "PENDING"):
                    require(not cats)
                else:
                    require((row["status"] == "NEEDS_REVIEW") == row["review_required"])
            result[key] = row
        return result

    truth = index(labels["cases"])
    for doc, version in ((baseline, BASELINE_VERSION), (revised, REVISED_VERSION)):
        exact(doc, ("evidence_source", "model", "protocol_version", "results"))
        require(doc["evidence_source"] in ("mocked", "real_gemini_api"))
        require(doc["protocol_version"] == version)
        require(isinstance(doc["model"], str) and
                re.fullmatch(r"models/gemini-[A-Za-z0-9._-]{1,120}", doc["model"]) and
                "AQ." not in doc["model"] and "AIza" not in doc["model"])
    require(baseline["evidence_source"] == revised["evidence_source"] and baseline["model"] == revised["model"])
    old, new = index(baseline["results"], True), index(revised["results"], True)
    require(truth.keys() == old.keys() == new.keys())
    scored = [key for key, row in truth.items() if row["categories"]]
    real = baseline["evidence_source"] == "real_gemini_api"

    def metrics(rows):
        usable = {key for key, row in rows.items() if row["status"] in ("PROPOSED", "NEEDS_REVIEW")}
        correct = sum(key in usable and set(rows[key]["categories"]) == set(truth[key]["categories"]) for key in scored)
        result = {
            "category_exact_matches": correct,
            "category_scored_cases": len(scored),
            "review_matches": sum(key in usable and rows[key]["review_required"] == truth[key]["review_required"] for key in truth),
            "joint_matches": sum(key in usable and set(rows[key]["categories"]) == set(truth[key]["categories"]) and
                                 rows[key]["review_required"] == truth[key]["review_required"] for key in truth),
            "needs_review": sum(row["status"] == "NEEDS_REVIEW" for row in rows.values()),
            "failed": sum(row["status"] == "FAILED" for row in rows.values()),
            "pending": sum(row["status"] == "PENDING" for row in rows.values()),
            "abstentions": sum(key in usable and not rows[key]["categories"] for key in truth),
        }
        if real:
            result["observed_exact_category_set_accuracy"] = correct / len(scored) if scored else None
        return result

    return {
        "evidence_source": baseline["evidence_source"],
        "comparison_kind": "operator_declared_real_predictions" if real else "mock_contract_only",
        "model": baseline["model"], "total_cases": len(truth),
        "baseline": metrics(old), "revised": metrics(new),
        "limitations": "No API calls or images are verified by this tool. Source labels are declarations. "
                        "Use actual matched photos, independently human-labeled categories and review expectations, "
                        "the same model/configuration, and no manual corrections. Failed/pending predictions remain "
                        "in the category denominator; unknown-category truth is scored separately by joint/review matches. "
                        "Mock counts are contract tests, never measured visual accuracy or calibrated confidence."
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("labels", type=pathlib.Path)
    parser.add_argument("baseline", type=pathlib.Path)
    parser.add_argument("revised", type=pathlib.Path)
    args = parser.parse_args()
    try:
        documents = []
        for path in (args.labels, args.baseline, args.revised):
            if path.stat().st_size > 5_000_000:
                raise ValueError()
            documents.append(json.loads(path.read_text(encoding="utf-8")))
        print(json.dumps(compare(*documents), indent=2))
    except (OSError, ValueError, TypeError, KeyError):
        # Never echo input paths, filenames or untrusted document contents.
        print("Comparison failed: invalid/unreadable local contract. No inputs were uploaded.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
