#!/usr/bin/env python3
"""Validate and compare Enhanced Queries and Stream Projections benchmark CSV files."""

import argparse
import csv
import json
from pathlib import Path
from typing import Optional
from zipfile import ZIP_DEFLATED, ZipFile


READ_REQUIRED_FIELDS = [
    "Run ID", "Solution", "Scenario ID", "Scenario", "Category", "Description", "Execution Path",
    "Result Status", "Expected Rows", "Observed Rows", "Average Latency (ms)", "P50 Latency (ms)",
    "P95 Latency (ms)", "Average Read Capacity Units", "Average Write Capacity Units",
    "Average DynamoDB Requests", "Total Read Capacity Units", "Total Write Capacity Units",
    "Total DynamoDB Requests", "AWS Region", "EC2 Instance Type", "DynamoDB Billing Mode",
    "Read Consistency", "Customer Count", "Orders Per Customer", "Warmup Iterations", "Measured Iterations",
]

CONFIG_FIELDS = [
    "Run ID", "AWS Region", "EC2 Instance Type", "DynamoDB Billing Mode", "Customer Count",
    "Orders Per Customer", "Warmup Iterations", "Measured Iterations",
]

METRIC_PAIRS = [
    ("Enhanced Queries Average Latency (ms)", "Stream Projections Average Latency (ms)"),
    ("Enhanced Queries P50 Latency (ms)", "Stream Projections P50 Latency (ms)"),
    ("Enhanced Queries P95 Latency (ms)", "Stream Projections P95 Latency (ms)"),
    ("Enhanced Queries Average Read Capacity Units", "Stream Projections Average Read Capacity Units"),
    ("Enhanced Queries Average DynamoDB Requests", "Stream Projections Average DynamoDB Requests"),
]

COMPARISON_FIELDS = [
    "Scenario ID", "Scenario", "Category", "Workload", "Enhanced Queries Execution Path",
    "Stream Projections Execution Path", "Enhanced Queries Result Status", "Stream Projections Result Status",
    "Enhanced Queries Average Latency (ms)", "Stream Projections Average Latency (ms)",
    "Enhanced Queries P50 Latency (ms)", "Stream Projections P50 Latency (ms)",
    "Enhanced Queries P95 Latency (ms)", "Stream Projections P95 Latency (ms)",
    "Enhanced Queries Average Read Capacity Units", "Stream Projections Average Read Capacity Units",
    "Enhanced Queries Average Write Capacity Units", "Stream Projections Average Write Capacity Units",
    "Enhanced Queries Average DynamoDB Requests", "Stream Projections Average DynamoDB Requests",
    "Enhanced Queries Total Read Capacity Units", "Stream Projections Total Read Capacity Units",
    "Enhanced Queries Total Write Capacity Units", "Stream Projections Total Write Capacity Units",
    "Enhanced Queries Total DynamoDB Requests", "Stream Projections Total DynamoDB Requests",
    "Latency Winner", "Read Capacity Winner", "Request Count Winner",
    "Stream Projections Latency Improvement (%)", "Stream Projections Read Capacity Improvement (%)",
    "Stream Projections Request Improvement (%)", "Overall Conclusion",
]

NUMERIC_COMPARISON_FIELDS = {
    field for pair in METRIC_PAIRS for field in pair
} | {
    "Enhanced Queries Average Write Capacity Units", "Stream Projections Average Write Capacity Units",
    "Enhanced Queries Total Read Capacity Units", "Stream Projections Total Read Capacity Units",
    "Enhanced Queries Total Write Capacity Units", "Stream Projections Total Write Capacity Units",
    "Enhanced Queries Total DynamoDB Requests", "Stream Projections Total DynamoDB Requests",
    "Stream Projections Latency Improvement (%)", "Stream Projections Read Capacity Improvement (%)",
    "Stream Projections Request Improvement (%)",
}


def number(value: str, field: str, scenario_id: str) -> float:
    try:
        return float(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{scenario_id}: {field} must be numeric, got {value!r}") from error


def fmt(value: float) -> str:
    return f"{value:.2f}"


def load_catalog(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as source:
        scenarios = json.load(source).get("scenarios", [])
    if len(scenarios) != 35:
        raise ValueError(f"catalog must contain 35 scenarios, found {len(scenarios)}")
    return scenarios


def load_and_validate(path: Path, expected_solution: str, expected_ids: list[str]) -> tuple[dict[str, dict[str, str]], dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        missing_headers = [field for field in READ_REQUIRED_FIELDS if field not in (reader.fieldnames or [])]
        if missing_headers:
            raise ValueError(f"{path}: missing required columns: {', '.join(missing_headers)}")
        rows = list(reader)
    indexed: dict[str, dict[str, str]] = {}
    for row in rows:
        scenario_id = row["Scenario ID"]
        if not scenario_id:
            raise ValueError(f"{path}: blank Scenario ID")
        if scenario_id in indexed:
            raise ValueError(f"{path}: duplicate Scenario ID {scenario_id}")
        for field in READ_REQUIRED_FIELDS:
            if row.get(field, "") == "":
                raise ValueError(f"{path}: {scenario_id} has a blank {field}")
        if row["Solution"] != expected_solution:
            raise ValueError(f"{path}: {scenario_id} identifies solution {row['Solution']!r}, expected {expected_solution!r}")
        if row["Result Status"] != "PASS":
            raise ValueError(f"{path}: {scenario_id} did not pass validation")
        for field in READ_REQUIRED_FIELDS[8:19]:
            number(row[field], field, scenario_id)
        indexed[scenario_id] = row
    if list(indexed) != expected_ids:
        missing = [key for key in expected_ids if key not in indexed]
        unexpected = [key for key in indexed if key not in expected_ids]
        raise ValueError(f"{path}: expected the canonical 35 scenarios in catalog order. Missing={missing}, unexpected={unexpected}")
    config = {field: indexed[expected_ids[0]][field] for field in CONFIG_FIELDS}
    for scenario_id, row in indexed.items():
        for field, value in config.items():
            if row[field] != value:
                raise ValueError(f"{path}: {scenario_id} has {field}={row[field]!r}, expected {value!r}")
    return indexed, config


def assert_compatible(eq_config: dict[str, str], sp_config: dict[str, str]) -> None:
    mismatches = [field for field in CONFIG_FIELDS if eq_config[field] != sp_config[field]]
    if mismatches:
        rendered = ", ".join(f"{field}: {eq_config[field]!r} != {sp_config[field]!r}" for field in mismatches)
        raise ValueError(f"benchmark runs are not comparable: {rendered}")


def winner(eq_value: float, sp_value: float) -> str:
    if eq_value < sp_value:
        return "Enhanced Queries"
    if sp_value < eq_value:
        return "Stream Projections"
    return "Tie"


def improvement(eq_value: float, sp_value: float) -> float:
    if eq_value == 0.0:
        return 0.0 if sp_value == 0.0 else -100.0
    return (eq_value - sp_value) * 100.0 / eq_value


def comparison_rows(catalog: list[dict], eq_rows: dict[str, dict[str, str]], sp_rows: dict[str, dict[str, str]]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    mapping = {
        "Average Latency (ms)": ("Enhanced Queries Average Latency (ms)", "Stream Projections Average Latency (ms)"),
        "P50 Latency (ms)": ("Enhanced Queries P50 Latency (ms)", "Stream Projections P50 Latency (ms)"),
        "P95 Latency (ms)": ("Enhanced Queries P95 Latency (ms)", "Stream Projections P95 Latency (ms)"),
        "Average Read Capacity Units": ("Enhanced Queries Average Read Capacity Units", "Stream Projections Average Read Capacity Units"),
        "Average Write Capacity Units": ("Enhanced Queries Average Write Capacity Units", "Stream Projections Average Write Capacity Units"),
        "Average DynamoDB Requests": ("Enhanced Queries Average DynamoDB Requests", "Stream Projections Average DynamoDB Requests"),
        "Total Read Capacity Units": ("Enhanced Queries Total Read Capacity Units", "Stream Projections Total Read Capacity Units"),
        "Total Write Capacity Units": ("Enhanced Queries Total Write Capacity Units", "Stream Projections Total Write Capacity Units"),
        "Total DynamoDB Requests": ("Enhanced Queries Total DynamoDB Requests", "Stream Projections Total DynamoDB Requests"),
    }
    for metadata in catalog:
        scenario_id = metadata["key"]
        eq, sp = eq_rows[scenario_id], sp_rows[scenario_id]
        row = {field: "" for field in COMPARISON_FIELDS}
        row.update({
            "Scenario ID": scenario_id,
            "Scenario": metadata["name"],
            "Category": metadata["category"],
            "Workload": metadata["workload"],
            "Enhanced Queries Execution Path": metadata["enhancedQueriesPath"],
            "Stream Projections Execution Path": metadata["streamProjectionsPath"],
            "Enhanced Queries Result Status": eq["Result Status"],
            "Stream Projections Result Status": sp["Result Status"],
        })
        for source_field, (eq_field, sp_field) in mapping.items():
            row[eq_field] = fmt(number(eq[source_field], source_field, scenario_id))
            row[sp_field] = fmt(number(sp[source_field], source_field, scenario_id))
        eq_latency = float(row["Enhanced Queries Average Latency (ms)"])
        sp_latency = float(row["Stream Projections Average Latency (ms)"])
        eq_rcu = float(row["Enhanced Queries Average Read Capacity Units"])
        sp_rcu = float(row["Stream Projections Average Read Capacity Units"])
        eq_requests = float(row["Enhanced Queries Average DynamoDB Requests"])
        sp_requests = float(row["Stream Projections Average DynamoDB Requests"])
        row["Latency Winner"] = winner(eq_latency, sp_latency)
        row["Read Capacity Winner"] = winner(eq_rcu, sp_rcu)
        row["Request Count Winner"] = winner(eq_requests, sp_requests)
        row["Stream Projections Latency Improvement (%)"] = fmt(improvement(eq_latency, sp_latency))
        row["Stream Projections Read Capacity Improvement (%)"] = fmt(improvement(eq_rcu, sp_rcu))
        row["Stream Projections Request Improvement (%)"] = fmt(improvement(eq_requests, sp_requests))
        row["Overall Conclusion"] = ("Stream Projections has lower average latency"
                                     if sp_latency < eq_latency else
                                     "Enhanced Queries has lower average latency"
                                     if eq_latency < sp_latency else "Average latency is equal")
        rows.append(row)
    return rows


def write_csv(path: Path, rows: list[dict[str, str]]) -> None:
    with path.open("w", newline="", encoding="utf-8") as target:
        writer = csv.DictWriter(target, fieldnames=COMPARISON_FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def excel_column(number_: int) -> str:
    result = ""
    while number_:
        number_, remainder = divmod(number_ - 1, 26)
        result = chr(65 + remainder) + result
    return result


def xml_escape(value: object) -> str:
    return (str(value).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;").replace("'", "&apos;"))


def write_xlsx(path: Path,
               rows: list[dict[str, str]],
               config: dict[str, str],
               lifecycle_csv: Optional[Path]) -> None:
    styles = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFC6EFCE"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFFFC7CE"/></patternFill></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFFFEB9C"/></patternFill></fill></fills>
<borders><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellXfs count="7"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" applyAlignment="1"><alignment wrapText="1"/></xf><xf numFmtId="2" fontId="0" fillId="0" borderId="0"/><xf numFmtId="2" fontId="0" fillId="2" borderId="0"/><xf numFmtId="2" fontId="0" fillId="3" borderId="0"/><xf numFmtId="2" fontId="0" fillId="4" borderId="0"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" applyAlignment="1"><alignment wrapText="1"/></xf></cellXfs>
</styleSheet>'''

    def cell(row_number: int, column_number: int, value: str, style: int, numeric: bool) -> str:
        ref = f"{excel_column(column_number)}{row_number}"
        if numeric:
            return f'<c r="{ref}" s="{style}" t="n"><v>{xml_escape(value)}</v></c>'
        return f'<c r="{ref}" s="{style}" t="inlineStr"><is><t>{xml_escape(value)}</t></is></c>'

    field_index = {field: index + 1 for index, field in enumerate(COMPARISON_FIELDS)}
    green, red, yellow = set(), set(), set()
    for row_number, row in enumerate(rows, start=2):
        for eq_field, sp_field in METRIC_PAIRS:
            eq_value, sp_value = float(row[eq_field]), float(row[sp_field])
            if eq_value < sp_value:
                green.add((row_number, field_index[eq_field])); red.add((row_number, field_index[sp_field]))
            elif sp_value < eq_value:
                red.add((row_number, field_index[eq_field])); green.add((row_number, field_index[sp_field]))
            else:
                yellow.add((row_number, field_index[eq_field])); yellow.add((row_number, field_index[sp_field]))
    data = []
    data.append('<row r="1">' + ''.join(cell(1, i, field, 1, False) for i, field in enumerate(COMPARISON_FIELDS, 1)) + '</row>')
    for row_number, row in enumerate(rows, start=2):
        cells = []
        for column_number, field in enumerate(COMPARISON_FIELDS, 1):
            style = 2 if field in NUMERIC_COMPARISON_FIELDS else 6
            if (row_number, column_number) in green:
                style = 3
            elif (row_number, column_number) in red:
                style = 4
            elif (row_number, column_number) in yellow:
                style = 5
            cells.append(cell(row_number, column_number, row[field], style, field in NUMERIC_COMPARISON_FIELDS))
        data.append(f'<row r="{row_number}">{"".join(cells)}</row>')
    comparison_sheet = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView showGridLines="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="1" width="35"/><col min="2" max="6" width="34"/><col min="7" max="8" width="20"/><col min="9" max="33" width="19"/></cols><sheetData>{"".join(data)}</sheetData><autoFilter ref="A1:{excel_column(len(COMPARISON_FIELDS))}{len(rows) + 1}"/></worksheet>'''

    summary_values = [
        ("Run ID", config["Run ID"]), ("Scenarios validated", str(len(rows))),
        ("Enhanced Queries summed average latency (ms)", fmt(sum(float(row["Enhanced Queries Average Latency (ms)"]) for row in rows))),
        ("Stream Projections summed average latency (ms)", fmt(sum(float(row["Stream Projections Average Latency (ms)"]) for row in rows))),
        ("Enhanced Queries summed average RCU", fmt(sum(float(row["Enhanced Queries Average Read Capacity Units"]) for row in rows))),
        ("Stream Projections summed average RCU", fmt(sum(float(row["Stream Projections Average Read Capacity Units"]) for row in rows))),
        ("Enhanced Queries summed average requests", fmt(sum(float(row["Enhanced Queries Average DynamoDB Requests"]) for row in rows))),
        ("Stream Projections summed average requests", fmt(sum(float(row["Stream Projections Average DynamoDB Requests"]) for row in rows))),
    ] + list(config.items())[1:]
    summary_rows = ['<row r="1"><c r="A1" s="1" t="inlineStr"><is><t>Benchmark summary</t></is></c></row>']
    for row_number, (label, value) in enumerate(summary_values, start=2):
        summary_rows.append(f'<row r="{row_number}"><c r="A{row_number}" s="1" t="inlineStr"><is><t>{xml_escape(label)}</t></is></c><c r="B{row_number}" s="6" t="inlineStr"><is><t>{xml_escape(value)}</t></is></c></row>')
    summary_sheet = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="1" width="48"/><col min="2" max="2" width="32"/></cols><sheetData>{"".join(summary_rows)}</sheetData></worksheet>'''

    sheets = [("Executive Summary", summary_sheet), ("Scenario Comparison", comparison_sheet)]
    if lifecycle_csv and lifecycle_csv.exists():
        with lifecycle_csv.open(newline="", encoding="utf-8") as source:
            lifecycle_rows = list(csv.reader(source))
        lifecycle_xml = []
        for row_number, values in enumerate(lifecycle_rows, start=1):
            lifecycle_xml.append('<row r="%d">%s</row>' % (row_number, ''.join(
                cell(row_number, column_number, value, 1 if row_number == 1 else 6, False)
                for column_number, value in enumerate(values, start=1))))
        sheets.append(("Lifecycle", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>' + ''.join(lifecycle_xml) + '</sheetData></worksheet>'))

    workbook_sheets = ''.join(f'<sheet name="{xml_escape(name)}" sheetId="{index}" r:id="rId{index}"/>' for index, (name, _) in enumerate(sheets, 1))
    workbook = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>{workbook_sheets}</sheets></workbook>'''
    relationships = ''.join(f'<Relationship Id="rId{index}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{index}.xml"/>' for index in range(1, len(sheets) + 1))
    relationships += f'<Relationship Id="rId{len(sheets) + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
    content_overrides = ''.join(f'<Override PartName="/xl/worksheets/sheet{index}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' for index in range(1, len(sheets) + 1))
    content_types = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>{content_overrides}<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>'''
    with ZipFile(path, "w", ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", content_types)
        archive.writestr("_rels/.rels", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        archive.writestr("xl/workbook.xml", workbook)
        archive.writestr("xl/_rels/workbook.xml.rels", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' + relationships + '</Relationships>')
        archive.writestr("xl/styles.xml", styles)
        for index, (_, sheet) in enumerate(sheets, 1):
            archive.writestr(f"xl/worksheets/sheet{index}.xml", sheet)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("enhanced_queries_csv", type=Path)
    parser.add_argument("stream_projections_csv", type=Path)
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--catalog", type=Path, default=Path(__file__).resolve().parents[1] / "benchmark-scenarios.json")
    parser.add_argument("--materialization-csv", type=Path)
    parser.add_argument("--comparison-out", default="Enhanced Queries vs Stream Projections - 1000 Customers x 1000 Orders.csv")
    parser.add_argument("--xlsx-out", default="Enhanced Queries vs Stream Projections - 1000 Customers x 1000 Orders.xlsx")
    args = parser.parse_args()
    catalog = load_catalog(args.catalog)
    scenario_ids = [entry["key"] for entry in catalog]
    enhanced, enhanced_config = load_and_validate(args.enhanced_queries_csv, "Enhanced Queries", scenario_ids)
    projections, projection_config = load_and_validate(args.stream_projections_csv, "Stream Projections", scenario_ids)
    assert_compatible(enhanced_config, projection_config)
    rows = comparison_rows(catalog, enhanced, projections)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    comparison_path = args.out_dir / args.comparison_out
    workbook_path = args.out_dir / args.xlsx_out
    write_csv(comparison_path, rows)
    write_xlsx(workbook_path, rows, enhanced_config, args.materialization_csv)
    print(f"Wrote {comparison_path}")
    print(f"Wrote {workbook_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
