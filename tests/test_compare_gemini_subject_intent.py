"""Synthetic comparison contracts only. No Google responses, image analysis or accuracy evidence."""
import copy
import unittest
from scripts.compare_gemini_subject_intent import compare, BASELINE_VERSION, REVISED_VERSION


class SubjectIntentComparisonTest(unittest.TestCase):
    def fixture(self):
        labels = {"configured_categories": ["people", "food", "nature", "buildings"], "cases": [
            {"id": "case-001", "categories": ["people"], "review_required": False},
            {"id": "case-002", "categories": ["nature"], "review_required": False},
            {"id": "case-003", "categories": ["people", "food"], "review_required": True},
            {"id": "case-004", "categories": [], "review_required": True},
        ]}
        def predictions(version):
            return {"evidence_source": "mocked", "model": "models/gemini-test", "protocol_version": version,
                    "results": [dict(row, status="NEEDS_REVIEW" if row["review_required"] else "PROPOSED",
                                     manually_corrected=False) for row in copy.deepcopy(labels["cases"])]}
        return labels, predictions(BASELINE_VERSION), predictions(REVISED_VERSION)

    def test_mock_comparison_is_not_an_accuracy_claim(self):
        labels, old, new = self.fixture()
        old["results"][0]["categories"] = ["food"]
        result = compare(labels, old, new)
        self.assertEqual("mock_contract_only", result["comparison_kind"])
        self.assertEqual(2, result["baseline"]["category_exact_matches"])
        self.assertEqual(3, result["revised"]["category_exact_matches"])
        self.assertNotIn("observed_exact_category_set_accuracy", result["revised"])
        self.assertEqual(4, result["revised"]["joint_matches"])

    def test_real_tag_only_selects_metric_formula_and_does_not_prove_an_api_call(self):
        # Still synthetic documents: exercise the reporting formula, never call Google.
        labels, old, new = self.fixture()
        old["evidence_source"] = new["evidence_source"] = "real_gemini_api"
        new["results"][0].update(categories=[], review_required=True, status="FAILED")
        result = compare(labels, old, new)
        self.assertAlmostEqual(2/3, result["revised"]["observed_exact_category_set_accuracy"])
        self.assertEqual(1, result["revised"]["failed"])
        self.assertIn("declarations", result["limitations"])

    def test_category_order_never_changes_multisubject_matching(self):
        labels, old, new = self.fixture()
        new["results"][2]["categories"].reverse()
        self.assertEqual(3, compare(labels, old, new)["revised"]["category_exact_matches"])

    def test_rejects_missing_duplicate_or_mismatched_cases(self):
        for change in (lambda rows: rows.pop(), lambda rows: rows.append(copy.deepcopy(rows[0])),
                       lambda rows: rows[0].update(id="case-999")):
            labels, old, new = self.fixture()
            change(new["results"])
            with self.assertRaises(ValueError):
                compare(labels, old, new)

    def test_rejects_mixed_sources_models_versions_and_corrected_results(self):
        for key, value in (("evidence_source", "real_gemini_api"), ("model", "models/gemini-different"),
                           ("protocol_version", BASELINE_VERSION)):
            labels, old, new = self.fixture()
            new[key] = value
            with self.assertRaises(ValueError):
                compare(labels, old, new)
        labels, old, new = self.fixture()
        new["results"][0]["manually_corrected"] = True
        with self.assertRaises(ValueError):
            compare(labels, old, new)

    def test_rejects_unknown_or_duplicate_categories_and_false_clear_multicategory(self):
        for cats, review in ((["unknown"], False), (["people", "people"], True), (["people", "food"], False)):
            labels, old, new = self.fixture()
            new["results"][0].update(categories=cats, review_required=review)
            with self.assertRaises(ValueError):
                compare(labels, old, new)

    def test_personal_fields_and_ids_are_not_accepted_or_exported(self):
        labels, old, new = self.fixture()
        new["results"][0]["filename"] = "/storage/private-name.jpg"
        with self.assertRaises(ValueError):
            compare(labels, old, new)
        new["results"][0].pop("filename")
        new["results"][0]["id"] = "private-name.jpg"
        with self.assertRaises(ValueError):
            compare(labels, old, new)
        labels, old, new = self.fixture()
        result = compare(labels, old, new)
        self.assertNotIn("case-001", str(result))

    def test_pending_and_abstaining_are_distinct(self):
        labels, old, new = self.fixture()
        new["results"][1].update(categories=[], review_required=True, status="PENDING")
        result = compare(labels, old, new)["revised"]
        self.assertEqual(1, result["pending"])
        self.assertEqual(1, result["abstentions"])
        self.assertEqual(2, result["category_exact_matches"])
        self.assertEqual(3, result["category_scored_cases"])
