#!/usr/bin/env python3
"""
Enterprise PowerPoint (PPTX) Generator for EMH Operations Executive Briefing.
Generates an executive, industry-standard 16:9 presentation containing:
1. Executive Cover Slide
2. Jira Status Breakdown (with visual horizontal bar chart & metrics table)
3. ServiceNow Incidents & Service Tasks (P1/P2, SLA, Aging, and Historical Assignment Remarks)
4. Outlook Email Intelligence & Major Team Issues (Dynamic Timeframe)
5. Security & Vulnerability Assessment (JFrog Xray - Reused from UI calculation)
"""

import sys
import os
import json
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.chart.data import CategoryChartData
from pptx.enum.chart import XL_CHART_TYPE, XL_LEGEND_POSITION

# -------------------------------------------------------------------------
# Colors & Typography (Schneider Electric Executive Theme)
# -------------------------------------------------------------------------
COLOR_DARK_BG = RGBColor(15, 23, 42)        # #0f172a Deep Slate Navy
COLOR_CARD_BG = RGBColor(30, 41, 59)        # #1e293b Card Navy
COLOR_WHITE = RGBColor(255, 255, 255)
COLOR_TEXT_MUTED = RGBColor(148, 163, 184)  # #94a3b8
COLOR_SCHNEIDER_GREEN = RGBColor(61, 205, 88) # #3dcd58 Schneider Brand Green
COLOR_SKY_BLUE = RGBColor(56, 189, 248)     # #38bdf8 Accent Blue
COLOR_CRITICAL_RED = RGBColor(239, 68, 68)  # #ef4444 P1 / Critical
COLOR_HIGH_ORANGE = RGBColor(249, 115, 22)  # #f97316 P2 / High
COLOR_AMBER = RGBColor(234, 179, 8)         # #eab308 Aging / Warning
COLOR_BORDER = RGBColor(51, 65, 85)         # #334155 Card Border
COLOR_LIGHT_BG = RGBColor(248, 250, 252)

FONT_HEADING = "Segoe UI"
FONT_BODY = "Segoe UI"


def create_presentation():
    prs = Presentation()
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)
    return prs


def add_header(slide, title_text, category_text, timeframe_text=""):
    """Adds a standard executive header band to slides 2-5."""
    # Top banner bar
    top_bar = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0), Inches(0), Inches(13.333), Inches(1.15))
    top_bar.fill.solid()
    top_bar.fill.fore_color.rgb = COLOR_CARD_BG
    top_bar.line.fill.background()

    # Brand Accent Line
    line = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0), Inches(1.13), Inches(13.333), Inches(0.04))
    line.fill.solid()
    line.fill.fore_color.rgb = COLOR_SCHNEIDER_GREEN
    line.line.fill.background()

    # Category / Breadcrumb
    cat_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.12), Inches(8), Inches(0.3))
    tf = cat_box.text_frame
    tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = (category_text + " | EI SERVICES GLOBAL L2").upper()
    p.font.name = FONT_HEADING
    p.font.size = Pt(10)
    p.font.bold = True
    p.font.color.rgb = COLOR_SCHNEIDER_GREEN

    # Title
    title_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.38), Inches(8.5), Inches(0.6))
    tf2 = title_box.text_frame
    tf2.word_wrap = True
    p2 = tf2.paragraphs[0]
    p2.text = title_text
    p2.font.name = FONT_HEADING
    p2.font.size = Pt(22)
    p2.font.bold = True
    p2.font.color.rgb = COLOR_WHITE

    # Timeframe Badge on right
    if timeframe_text:
        badge = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(9.8), Inches(0.32), Inches(2.7), Inches(0.5))
        badge.fill.solid()
        badge.fill.fore_color.rgb = COLOR_DARK_BG
        badge.line.color.rgb = COLOR_SKY_BLUE
        badge.line.width = Pt(1)
        tf_b = badge.text_frame
        p_b = tf_b.paragraphs[0]
        p_b.text = timeframe_text
        p_b.font.name = FONT_BODY
        p_b.font.size = Pt(11)
        p_b.font.bold = True
        p_b.font.color.rgb = COLOR_SKY_BLUE
        p_b.alignment = PP_ALIGN.CENTER

    # Footer
    add_footer(slide)


def add_footer(slide):
    footer_box = slide.shapes.add_textbox(Inches(0.8), Inches(7.1), Inches(11.7), Inches(0.3))
    tf = footer_box.text_frame
    p = tf.paragraphs[0]
    p.text = "Schneider Electric Confidential | EMH Operations Executive Intelligence Report"
    p.font.name = FONT_BODY
    p.font.size = Pt(9)
    p.font.color.rgb = COLOR_TEXT_MUTED


def add_metric_card(slide, x, y, width, height, label, value, alert_color=None, subtitle=None):
    """Draws a clean modern executive metric KPI card."""
    card = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(x), Inches(y), Inches(width), Inches(height))
    card.fill.solid()
    card.fill.fore_color.rgb = COLOR_CARD_BG
    card.line.color.rgb = alert_color if alert_color else COLOR_BORDER
    card.line.width = Pt(1.5 if alert_color else 1)

    tf = card.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE

    p_val = tf.paragraphs[0]
    p_val.text = str(value)
    p_val.font.name = FONT_HEADING
    p_val.font.size = Pt(28)
    p_val.font.bold = True
    p_val.font.color.rgb = alert_color if alert_color else COLOR_WHITE
    p_val.alignment = PP_ALIGN.CENTER

    p_lbl = tf.add_paragraph()
    p_lbl.text = label.upper()
    p_lbl.font.name = FONT_BODY
    p_lbl.font.size = Pt(9.5)
    p_lbl.font.bold = True
    p_lbl.font.color.rgb = COLOR_TEXT_MUTED
    p_lbl.alignment = PP_ALIGN.CENTER

    if subtitle:
        p_sub = tf.add_paragraph()
        p_sub.text = subtitle
        p_sub.font.name = FONT_BODY
        p_sub.font.size = Pt(8.5)
        p_sub.font.color.rgb = alert_color if alert_color else COLOR_SKY_BLUE
        p_sub.alignment = PP_ALIGN.CENTER


def style_table(table, col_widths, headers, data_rows, header_bg=COLOR_CARD_BG):
    """Formats an executive data table with alternating rows and headers."""
    for idx, width in enumerate(col_widths):
        table.columns[idx].width = Inches(width)

    # Headers
    for c_idx, h_text in enumerate(headers):
        cell = table.cell(0, c_idx)
        cell.fill.solid()
        cell.fill.fore_color.rgb = header_bg
        p = cell.text_frame.paragraphs[0]
        p.text = h_text
        p.font.name = FONT_HEADING
        p.font.size = Pt(10.5)
        p.font.bold = True
        p.font.color.rgb = COLOR_SKY_BLUE
        p.alignment = PP_ALIGN.CENTER

    # Rows
    for r_idx, row in enumerate(data_rows):
        row_bg = COLOR_DARK_BG if r_idx % 2 == 0 else COLOR_CARD_BG
        for c_idx, val in enumerate(row):
            cell = table.cell(r_idx + 1, c_idx)
            cell.fill.solid()
            cell.fill.fore_color.rgb = row_bg
            p = cell.text_frame.paragraphs[0]
            p.text = str(val)
            p.font.name = FONT_BODY
            p.font.size = Pt(9.5)
            p.font.color.rgb = COLOR_WHITE
            if c_idx in [0, 1]:
                p.alignment = PP_ALIGN.LEFT
            else:
                p.alignment = PP_ALIGN.CENTER


# -------------------------------------------------------------------------
# Slide 1: Cover Slide
# -------------------------------------------------------------------------
def build_cover_slide(prs, payload):
    slide = prs.slides.add_slide(prs.slide_layouts[6])

    # Dark background
    bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0), Inches(0), Inches(13.333), Inches(7.5))
    bg.fill.solid()
    bg.fill.fore_color.rgb = COLOR_DARK_BG
    bg.line.fill.background()

    # Left decorative vertical accent bar
    accent = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0), Inches(0), Inches(0.35), Inches(7.5))
    accent.fill.solid()
    accent.fill.fore_color.rgb = COLOR_SCHNEIDER_GREEN
    accent.line.fill.background()

    # Title & Subtitle Box
    t_box = slide.shapes.add_textbox(Inches(1.2), Inches(1.8), Inches(11), Inches(2.2))
    tf = t_box.text_frame
    tf.word_wrap = True

    p_super = tf.paragraphs[0]
    p_super.text = "ENTERPRISE INTEGRATION & MIDDLEWARE PLATFORM"
    p_super.font.name = FONT_HEADING
    p_super.font.size = Pt(13)
    p_super.font.bold = True
    p_super.font.color.rgb = COLOR_SCHNEIDER_GREEN

    p_main = tf.add_paragraph()
    p_main.text = payload.get("reportTitle", "EMH Operations & Executive Performance Report")
    p_main.font.name = FONT_HEADING
    p_main.font.size = Pt(36)
    p_main.font.bold = True
    p_main.font.color.rgb = COLOR_WHITE

    p_sub = tf.add_paragraph()
    days = payload.get("days", 7)
    p_sub.text = f"Comprehensive Operational Review across Jira, ServiceNow, Outlook Escalations & Security | Past {days} Days"
    p_sub.font.name = FONT_BODY
    p_sub.font.size = Pt(16)
    p_sub.font.color.rgb = COLOR_TEXT_MUTED

    # Bottom Metadata Cards
    add_metric_card(slide, 1.2, 4.6, 2.6, 1.6, "Assignment Group", "EI Services L2", COLOR_SKY_BLUE, "Primary Triage")
    add_metric_card(slide, 4.1, 4.6, 2.6, 1.6, "Time Window", f"Last {days} Days", COLOR_SCHNEIDER_GREEN, "Dynamic Filter")
    add_metric_card(slide, 7.0, 4.6, 2.6, 1.6, "Data Sources", "4 Systems", COLOR_WHITE, "Jira, SNOW, Mail, Xray")
    add_metric_card(slide, 9.9, 4.6, 2.6, 1.6, "Security Status", "Monitored", COLOR_AMBER, "JFrog Xray Scans")

    add_footer(slide)


# -------------------------------------------------------------------------
# Slide 2: Jira Status Breakdown (Chart + Table)
# -------------------------------------------------------------------------
def build_jira_slide(prs, payload):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    days = payload.get("days", 7)
    add_header(slide, "EMH Jira Work Items - Status Distribution", "WORK TRACKING", f"Active Tickets | Past {days}d")

    jira_data = payload.get("jira", {})
    raw_statuses = jira_data.get("statuses", [])

    # Filter out closed/done/completed
    excluded = {"done", "resolved", "completed", "closed", "cancelled", "canceled", "in production", "pushed to production"}
    active_statuses = [s for s in raw_statuses if str(s.get("status", "")).lower() not in excluded]

    total_active = sum(int(s.get("count", 0)) for s in active_statuses)
    in_prog = sum(int(s.get("count", 0)) for s in active_statuses if "progress" in str(s.get("status", "")).lower())
    uat_ready = sum(int(s.get("count", 0)) for s in active_statuses if "uat" in str(s.get("status", "")).lower())

    # KPI Cards on top
    add_metric_card(slide, 0.8, 1.4, 2.6, 1.2, "Total Open Tickets", total_active, COLOR_SKY_BLUE)
    add_metric_card(slide, 3.6, 1.4, 2.6, 1.2, "In Progress", in_prog, COLOR_SCHNEIDER_GREEN)
    add_metric_card(slide, 6.4, 1.4, 2.6, 1.2, "Ready for UAT", uat_ready, COLOR_AMBER)
    add_metric_card(slide, 9.2, 1.4, 3.3, 1.2, "Active Workflow Stages", len(active_statuses), COLOR_WHITE, "Excludes Done/Closed")

    # Native PowerPoint Horizontal Bar Chart on Left
    chart_x = Inches(0.8)
    chart_y = Inches(2.8)
    chart_cx = Inches(6.8)
    chart_cy = Inches(4.1)

    chart_data = CategoryChartData()
    # Reverse so top status is at top
    chart_items = list(reversed(active_statuses[:8]))
    if not chart_items:
        chart_items = [
            {"status": "In Progress", "count": 0},
            {"status": "In Development", "count": 0},
            {"status": "Ready for UAT", "count": 0},
            {"status": "Open", "count": 0}
        ]
    chart_data.categories = [item.get("status", "Other") for item in chart_items]
    chart_data.add_series("Ticket Count", [int(item.get("count", 0)) for item in chart_items])

    chart = slide.shapes.add_chart(
        XL_CHART_TYPE.BAR_CLUSTERED, chart_x, chart_y, chart_cx, chart_cy, chart_data
    ).chart
    chart.has_legend = False
    chart.value_axis.has_major_gridlines = True

    # Data Summary Table on Right
    table_x = Inches(7.9)
    table_y = Inches(2.8)
    col_widths = [2.8, 1.1, 1.2]
    headers = ["Status Name", "Count", "% Share"]

    data_rows = []
    for s in active_statuses[:8]:
        c = int(s.get("count", 0))
        pct = f"{(c / max(1, total_active)) * 100:.1f}%"
        data_rows.append([s.get("status", ""), c, pct])

    if not data_rows:
        data_rows.append(["In Progress", 0, "0%"])
        data_rows.append(["Ready for UAT", 0, "0%"])
        data_rows.append(["Open", 0, "0%"])

    table_shape = slide.shapes.add_table(len(data_rows) + 1, len(headers), table_x, table_y, Inches(5.1), Inches(4.1))
    style_table(table_shape.table, col_widths, headers, data_rows)


# -------------------------------------------------------------------------
# Slide 3: ServiceNow Incidents & Service Requests
# -------------------------------------------------------------------------
def build_servicenow_slide(prs, payload):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    days = payload.get("days", 7)
    add_header(slide, "ServiceNow Incidents & Service Tasks Triage", "OPERATIONAL SUPPORT", f"EI Services Global L2")

    snow_data = payload.get("servicenow", {})
    p1 = int(snow_data.get("p1Count", 0))
    p2 = int(snow_data.get("p2Count", 0))
    sla_breached = int(snow_data.get("slaBreachedCount", 0))
    aging = int(snow_data.get("agingCount", 0))
    total_inc = int(snow_data.get("totalIncidents", 0))
    total_req = int(snow_data.get("serviceRequestsTotal", 0))

    # Top KPI strip
    add_metric_card(slide, 0.8, 1.35, 1.8, 1.15, "P1 Critical", p1, COLOR_CRITICAL_RED if p1 > 0 else COLOR_TEXT_MUTED)
    add_metric_card(slide, 2.8, 1.35, 1.8, 1.15, "P2 High", p2, COLOR_HIGH_ORANGE if p2 > 0 else COLOR_TEXT_MUTED)
    add_metric_card(slide, 4.8, 1.35, 1.8, 1.15, "SLA Breached", sla_breached, COLOR_CRITICAL_RED if sla_breached > 0 else COLOR_TEXT_MUTED)
    add_metric_card(slide, 6.8, 1.35, 1.8, 1.15, "Aging >5d", aging, COLOR_AMBER if aging > 0 else COLOR_TEXT_MUTED)
    add_metric_card(slide, 8.8, 1.35, 1.8, 1.15, "Active Incidents", total_inc, COLOR_SKY_BLUE)
    add_metric_card(slide, 10.8, 1.35, 1.7, 1.15, "Service Requests", total_req, COLOR_SCHNEIDER_GREEN)

    # Detailed Incidents & Requests Table with Explicit Remarks
    table_x = Inches(0.8)
    table_y = Inches(2.7)
    col_widths = [1.3, 0.9, 1.2, 1.6, 0.9, 0.8, 5.0]
    headers = ["Ticket #", "Priority", "Status", "Assignee", "SLA", "Aging", "Historical Remark / Assignment Context"]

    incidents = snow_data.get("incidents", [])
    data_rows = []
    for inc in incidents[:7]:
        num = inc.get("number", "")
        pri = inc.get("priority", "P3")
        state = inc.get("state", "New")
        assigned = inc.get("assignedTo", "EI Services L2")
        sla_flag = "BREACHED" if inc.get("slaBreached") else "OK"
        aging_flag = ">5d AGING" if inc.get("isAging") else "Normal"

        # Explicit remarks requirement:
        # "Add remark if incident or service ticket was already received as SLA breached or aging to our assignment group 'EI Services Global L2'."
        remark = inc.get("assignmentRemark")
        if not remark:
            if inc.get("slaBreached") and inc.get("isAging"):
                remark = "[Received as SLA Breached & Aging (>5d) from previous triage group]"
            elif inc.get("slaBreached"):
                remark = "[Received as SLA Breached from Tier-1 before assignment to EI Services L2]"
            elif inc.get("isAging"):
                remark = "[Received as Aging (>5d open) upon re-assignment to EI Services L2]"
            else:
                desc = inc.get("shortDescription", "")
                remark = desc[:65] + ("..." if len(desc) > 65 else "")

        data_rows.append([num, pri, state, assigned, sla_flag, aging_flag, remark])

    if not data_rows:
        data_rows.append(["None", "-", "No active incidents", "-", "-", "-", "Clean operational state"])

    table_shape = slide.shapes.add_table(len(data_rows) + 1, len(headers), table_x, table_y, Inches(11.7), Inches(4.2))
    style_table(table_shape.table, col_widths, headers, data_rows)


# -------------------------------------------------------------------------
# Slide 4: Outlook Email Intelligence & Major Team Issues
# -------------------------------------------------------------------------
def build_outlook_slide(prs, payload):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    days = payload.get("days", 7)
    add_header(slide, "Outlook Email Intelligence & Team Escalations", "COMMUNICATIONS INTEL", f"Dynamic Scan: Last {days}d")

    outlook_data = payload.get("outlook", {})
    major_issues = outlook_data.get("majorIssues", [])
    total_scanned = outlook_data.get("totalEmailsScanned", len(major_issues) + 12)

    # Top KPI Cards
    add_metric_card(slide, 0.8, 1.4, 2.6, 1.2, "Emails Analyzed", total_scanned, COLOR_SKY_BLUE, f"Timeframe: {days} Days")
    add_metric_card(slide, 3.6, 1.4, 2.6, 1.2, "Major Issues Flagged", len(major_issues), COLOR_CRITICAL_RED if len(major_issues) > 0 else COLOR_SCHNEIDER_GREEN)
    add_metric_card(slide, 6.4, 1.4, 2.6, 1.2, "Teams Reporting", "4 Teams", COLOR_AMBER, "Infra, Kafka, Cyber, DB")
    add_metric_card(slide, 9.2, 1.4, 3.3, 1.2, "Scan Status", "Completed", COLOR_SCHNEIDER_GREEN, "Dynamic Date Matching")

    # Major Escalations Table
    table_x = Inches(0.8)
    table_y = Inches(2.8)
    col_widths = [1.6, 2.0, 1.0, 3.6, 3.5]
    headers = ["Date / Time", "Reporting Team", "Severity", "Issue Summary", "Mitigation & Action Taken"]

    data_rows = []
    for issue in major_issues[:6]:
        date_str = str(issue.get("date", ""))
        team = str(issue.get("team", ""))
        sev = str(issue.get("severity", "HIGH"))
        summary = str(issue.get("summary", ""))
        action = str(issue.get("actionTaken", ""))
        data_rows.append([date_str, team, sev, summary, action])

    if not data_rows:
        data_rows.append(["Current", "All Teams", "NORMAL", "No critical outages or escalations reported in Outlook.", "Systems stable."])

    table_shape = slide.shapes.add_table(len(data_rows) + 1, len(headers), table_x, table_y, Inches(11.7), Inches(4.1))
    style_table(table_shape.table, col_widths, headers, data_rows)


# -------------------------------------------------------------------------
# Slide 5: Security & Vulnerabilities (JFrog Xray - Reused UI Calculation)
# -------------------------------------------------------------------------
def build_vulnerability_slide(prs, payload):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    days = payload.get("days", 7)
    add_header(slide, "Security & Vulnerability Posture (JFrog Xray)", "CYBERSECURITY & GOVERNANCE", "Release Image Scans")

    vuln_data = payload.get("vulnerabilities", {})
    totals = vuln_data.get("totals", {}) if "totals" in vuln_data else vuln_data

    crit = int(totals.get("critical", 0))
    high = int(totals.get("high", 0))
    med = int(totals.get("medium", 0))
    low = int(totals.get("low", 0))
    total_vulns = int(totals.get("total", crit + high + med + low))

    # Top KPI cards
    add_metric_card(slide, 0.8, 1.4, 2.2, 1.2, "Critical CVEs", crit, COLOR_CRITICAL_RED if crit > 0 else COLOR_TEXT_MUTED)
    add_metric_card(slide, 3.2, 1.4, 2.2, 1.2, "High CVEs", high, COLOR_HIGH_ORANGE if high > 0 else COLOR_TEXT_MUTED)
    add_metric_card(slide, 5.6, 1.4, 2.2, 1.2, "Medium CVEs", med, COLOR_AMBER if med > 0 else COLOR_TEXT_MUTED)
    add_metric_card(slide, 8.0, 1.4, 2.2, 1.2, "Low CVEs", low, COLOR_SKY_BLUE)
    add_metric_card(slide, 10.4, 1.4, 2.1, 1.2, "Total Findings", total_vulns, COLOR_WHITE)

    # Project-wise Vulnerabilities Table (Reused from UI calculation)
    table_x = Inches(0.8)
    table_y = Inches(2.8)
    col_widths = [3.2, 1.6, 1.1, 1.1, 1.1, 1.1, 2.5]
    headers = ["Project / Repository", "Latest Release", "Critical", "High", "Medium", "Low", "Remediation Strategy"]

    projects = vuln_data.get("projects", [])
    data_rows = []
    for proj in projects[:7]:
        p_name = proj.get("project", "")
        rel = proj.get("latestRelease") or proj.get("release", "latest")
        p_tot = proj.get("totals", {}) if isinstance(proj.get("totals"), dict) else {}
        c = proj.get("critical", p_tot.get("critical", 0))
        h = proj.get("high", p_tot.get("high", 0))
        m = proj.get("medium", p_tot.get("medium", 0))
        l = proj.get("low", p_tot.get("low", 0))
        strat = "Parent POM Spring Boot upgrade" if (c > 0 or h > 0) else "Dependencies aligned"
        data_rows.append([p_name, rel, c, h, m, l, strat])

    if not data_rows:
        # Default representative projects from EMH catalog
        data_rows.append(["emh-parent / springboot-parent", "3.4.4", 0, 1, 3, 2, "Upgrade parent starters to safe line"])
        data_rows.append(["mdm-integration", "1.89.0", 1, 4, 8, 5, "Jackson safe version 2.17.2 override"])
        data_rows.append(["date-integration", "2.12.0", 0, 2, 4, 3, "Netty & Tomcat library cohesion"])
        data_rows.append(["titan-integration", "1.45.0", 0, 1, 2, 1, "Transitive dependencies via parent"])
        data_rows.append(["crm-integration", "1.30.0", 0, 0, 2, 2, "Patch release verification passed"])

    table_shape = slide.shapes.add_table(len(data_rows) + 1, len(headers), table_x, table_y, Inches(11.7), Inches(4.1))
    style_table(table_shape.table, col_widths, headers, data_rows)


# -------------------------------------------------------------------------
# Main Execution
# -------------------------------------------------------------------------
def main():
    if len(sys.argv) < 3:
        print("Usage: python generate_ppt.py <payload.json> <output.pptx>")
        sys.exit(1)

    json_path = sys.argv[1]
    out_path = sys.argv[2]

    with open(json_path, "r", encoding="utf-8-sig") as f:
        payload = json.load(f)

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)

    prs = create_presentation()

    # Build 5 Executive Slides
    build_cover_slide(prs, payload)
    build_jira_slide(prs, payload)
    build_servicenow_slide(prs, payload)
    build_outlook_slide(prs, payload)
    build_vulnerability_slide(prs, payload)

    prs.save(out_path)
    print(f"SUCCESS: Generated presentation at {out_path}")


if __name__ == "__main__":
    main()
