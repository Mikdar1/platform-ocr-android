// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.tencent.ppocrv5ncnn;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpatialExtractor {

    private static final Pattern PATTERN_NIK = Pattern.compile("\\d{16}");
    private static final Pattern PATTERN_DATE = Pattern.compile("\\d{2}[-/]\\d{2}[-/]\\d{4}");
    private static final Pattern PATTERN_RT_RW = Pattern.compile("\\d{3}/\\d{3}");
    private static final Pattern PATTERN_ROW_NUMBER = Pattern.compile("^\\d{1,2}$");

    private static class TextBox {
        String text;
        float x, y, w, h, cx, cy;

        TextBox(String text, float x, float y, float w, float h, float cx, float cy) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.cx = cx;
            this.cy = cy;
        }

        float right() { return x + w; }
    }

    private static class TableColumn {
        String header;
        float xMin, xMax, xCenter;

        TableColumn(String header, float xMin, float xMax) {
            this.header = header;
            this.xMin = xMin;
            this.xMax = xMax;
            this.xCenter = (xMin + xMax) / 2;
        }
    }

    private static class TableRow {
        int rowNumber = -1;
        Map<String, String> values = new HashMap<>();
    }

    // ---- public API ----

    /**
     * Reconstruct a spatial text layout from OCR JSON with bounding boxes.
     * Groups boxes into rows by Y coordinate and separates columns with " | ".
     * This preserves the tabular structure for the LLM to understand.
     */
    public static String toSpatialText(String ocrJsonWithBoxes) {
        try {
            List<TextBox> textBoxes = parseBoxes(ocrJsonWithBoxes);
            if (textBoxes.isEmpty()) return "";

            float rowThreshold = calculateAverageHeight(textBoxes) * 0.7f;
            sortByReadingOrder(textBoxes, rowThreshold);
            List<List<TextBox>> rows = groupIntoRows(textBoxes, rowThreshold);

            StringBuilder sb = new StringBuilder();
            for (List<TextBox> row : rows) {
                // Calculate gaps between boxes to detect column boundaries
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) {
                        float gap = row.get(i).x - row.get(i - 1).right();
                        float avgW = row.get(i - 1).h * 0.8f; // approximate char width
                        if (gap > avgW * 3) {
                            sb.append(" | ");
                        } else {
                            sb.append(" ");
                        }
                    }
                    sb.append(row.get(i).text);
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Rule-based structured extraction from OCR JSON with bounding boxes.
     */
    public static String extract(String ocrJsonWithBoxes) {
        try {
            List<TextBox> textBoxes = parseBoxes(ocrJsonWithBoxes);
            if (textBoxes.isEmpty()) {
                return "{\"error\": \"No text detected\"}";
            }

            float avgHeight = calculateAverageHeight(textBoxes);
            float rowThreshold = avgHeight * 0.7f;

            sortByReadingOrder(textBoxes, rowThreshold);
            List<List<TextBox>> rows = groupIntoRows(textBoxes, rowThreshold);

            JSONObject result = new JSONObject();

            // Extract header information
            extractHeaderInfo(result, textBoxes, rows, rowThreshold);

            // Find table header rows by joining row text and matching keywords.
            // Table 1 keywords (must match at least 3):
            String[] t1Keywords = {"NIK", "Nama", "Kelamin", "Tempat", "Lahir", "Agama", "Pendidikan", "Pekerjaan"};
            // Table 2 keywords:
            String[] t2Keywords = {"Perkawinan", "Hubungan", "Kewarganegaraan", "Ayah", "Ibu"};

            int t1HeaderIdx = findHeaderRow(rows, t1Keywords, 3);
            int t2HeaderIdx = findHeaderRow(rows, t2Keywords, 3);

            List<TableColumn> t1Columns = (t1HeaderIdx >= 0) ? buildColumns(rows.get(t1HeaderIdx), t1Keywords) : new ArrayList<TableColumn>();
            List<TableColumn> t2Columns = (t2HeaderIdx >= 0) ? buildColumns(rows.get(t2HeaderIdx), t2Keywords) : new ArrayList<TableColumn>();

            // Determine table boundaries
            int t1End = (t2HeaderIdx > t1HeaderIdx && t2HeaderIdx > 0) ? t2HeaderIdx : rows.size();
            int t2End = rows.size();

            List<TableRow> t1Rows = parseDataRows(rows, t1HeaderIdx, t1End, t1Columns);
            List<TableRow> t2Rows = parseDataRows(rows, t2HeaderIdx, t2End, t2Columns);

            JSONArray members = mergeTablesToMembers(t1Rows, t2Rows, textBoxes);
            result.put("anggota_keluarga", members);

            return result.toString(2);

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ---- parsing helpers ----

    private static List<TextBox> parseBoxes(String ocrJsonWithBoxes) throws Exception {
        JSONArray boxes = new JSONArray(ocrJsonWithBoxes);
        List<TextBox> textBoxes = new ArrayList<>();
        for (int i = 0; i < boxes.length(); i++) {
            JSONObject box = boxes.getJSONObject(i);
            textBoxes.add(new TextBox(
                box.getString("text"),
                (float) box.getDouble("x"),
                (float) box.getDouble("y"),
                (float) box.getDouble("w"),
                (float) box.getDouble("h"),
                (float) box.getDouble("cx"),
                (float) box.getDouble("cy")
            ));
        }
        return textBoxes;
    }

    private static float calculateAverageHeight(List<TextBox> boxes) {
        if (boxes.isEmpty()) return 20f;
        float sum = 0;
        for (TextBox box : boxes) sum += box.h;
        return sum / boxes.size();
    }

    private static void sortByReadingOrder(List<TextBox> boxes, final float rowThreshold) {
        Collections.sort(boxes, new Comparator<TextBox>() {
            @Override
            public int compare(TextBox a, TextBox b) {
                if (Math.abs(a.cy - b.cy) < rowThreshold) {
                    return Float.compare(a.cx, b.cx);
                }
                return Float.compare(a.cy, b.cy);
            }
        });
    }

    private static List<List<TextBox>> groupIntoRows(List<TextBox> boxes, float threshold) {
        List<List<TextBox>> rows = new ArrayList<>();
        if (boxes.isEmpty()) return rows;

        List<TextBox> currentRow = new ArrayList<>();
        float currentY = boxes.get(0).cy;

        for (TextBox box : boxes) {
            if (Math.abs(box.cy - currentY) > threshold) {
                if (!currentRow.isEmpty()) rows.add(currentRow);
                currentRow = new ArrayList<>();
                currentY = box.cy;
            }
            currentRow.add(box);
        }
        if (!currentRow.isEmpty()) rows.add(currentRow);
        return rows;
    }

    /** Join all text in a row into a single string for keyword matching. */
    private static String joinRowText(List<TextBox> row) {
        StringBuilder sb = new StringBuilder();
        for (TextBox box : row) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(box.text);
        }
        return sb.toString();
    }

    // ---- header info extraction ----

    private static void extractHeaderInfo(JSONObject result, List<TextBox> allBoxes,
                                          List<List<TextBox>> rows, float rowThreshold) throws Exception {
        // Find KK number: first 16-digit number
        String kkNumber = "";
        for (TextBox box : allBoxes) {
            Matcher m = PATTERN_NIK.matcher(box.text);
            if (m.find()) { kkNumber = m.group(); break; }
        }
        result.put("no_kk", kkNumber);

        // For labeled fields, search the joined row text so split labels like
        // "Nama" + "Kepala Keluarga" on the same row still match.
        result.put("kepala_keluarga", findLabelValueInRows(rows, new String[]{"Kepala Keluarga"}, rowThreshold));
        result.put("alamat", findLabelValueInRows(rows, new String[]{"Alamat"}, rowThreshold));
        result.put("rt_rw", findPatternValue(allBoxes, PATTERN_RT_RW));
        result.put("desa_kelurahan", findLabelValueInRows(rows, new String[]{"Desa/Kelurahan", "Desa", "Kelurahan"}, rowThreshold));
        result.put("kecamatan", findLabelValueInRows(rows, new String[]{"Kecamatan"}, rowThreshold));
        result.put("kabupaten_kota", findLabelValueInRows(rows, new String[]{"Kabupaten/Kota", "Kabupaten"}, rowThreshold));
        result.put("provinsi", findLabelValueInRows(rows, new String[]{"Provinsi"}, rowThreshold));
    }

    /**
     * Search rows for a label. When found, return the value:
     * 1) text after ":" in the same box
     * 2) text after ":" in the joined row
     * 3) the next non-label box to the right
     */
    private static String findLabelValueInRows(List<List<TextBox>> rows, String[] labelVariants, float rowThreshold) {
        for (List<TextBox> row : rows) {
            String joined = joinRowText(row);
            String joinedLower = joined.toLowerCase();

            for (String label : labelVariants) {
                if (!joinedLower.contains(label.toLowerCase())) continue;

                // Check if value is after ":" in the joined row text
                int colonIdx = joined.indexOf(':');
                if (colonIdx >= 0 && colonIdx < joined.length() - 1) {
                    String afterColon = joined.substring(colonIdx + 1).trim();
                    if (!afterColon.isEmpty()) return afterColon;
                }

                // Otherwise, look for value boxes to the right of the label box
                for (int i = 0; i < row.size(); i++) {
                    TextBox box = row.get(i);
                    if (box.text.toLowerCase().contains(label.toLowerCase())) {
                        // Return the next box that isn't a label
                        for (int j = i + 1; j < row.size(); j++) {
                            TextBox next = row.get(j);
                            if (!isLabelText(next.text)) {
                                return next.text.trim();
                            }
                        }
                    }
                }
            }
        }
        return "";
    }

    private static String findPatternValue(List<TextBox> boxes, Pattern pattern) {
        for (TextBox box : boxes) {
            Matcher m = pattern.matcher(box.text);
            if (m.find()) return m.group();
        }
        return "";
    }

    // ---- table parsing ----

    /**
     * Find a header row by joining all text in each row and checking if it
     * contains at least `minMatches` of the given keywords.
     * This handles OCR splitting multi-word headers across boxes.
     */
    private static int findHeaderRow(List<List<TextBox>> rows, String[] keywords, int minMatches) {
        for (int i = 0; i < rows.size(); i++) {
            String rowText = joinRowText(rows.get(i)).toLowerCase();
            int matchCount = 0;
            for (String kw : keywords) {
                if (rowText.contains(kw.toLowerCase())) matchCount++;
            }
            if (matchCount >= minMatches) return i;
        }
        return -1;
    }

    /**
     * Build column definitions from a header row.
     * Each box in the header row is mapped to the best-matching keyword.
     * Adjacent boxes that don't match any keyword are merged into the previous column.
     */
    private static List<TableColumn> buildColumns(List<TextBox> headerRow, String[] keywords) {
        // First, assign each box a keyword if it matches
        List<TableColumn> columns = new ArrayList<>();
        Map<String, TableColumn> seen = new HashMap<>();

        for (TextBox box : headerRow) {
            String bestKw = null;
            String boxLower = box.text.toLowerCase();
            for (String kw : keywords) {
                if (boxLower.contains(kw.toLowerCase())) {
                    bestKw = kw;
                    break;
                }
            }

            if (bestKw != null) {
                if (seen.containsKey(bestKw)) {
                    // Extend existing column range
                    TableColumn existing = seen.get(bestKw);
                    existing.xMin = Math.min(existing.xMin, box.x);
                    existing.xMax = Math.max(existing.xMax, box.right());
                    existing.xCenter = (existing.xMin + existing.xMax) / 2;
                } else {
                    TableColumn col = new TableColumn(bestKw, box.x, box.right());
                    columns.add(col);
                    seen.put(bestKw, col);
                }
            } else if (!columns.isEmpty()) {
                // Non-matching box: extend the previous column's range
                // (handles split headers like "Jenis" + "Kelamin" where "Kelamin" matches)
                // Actually skip - only extend if there's a small gap
            }
        }

        // Also add a "No" column if the first box looks like "No" or row starts at far left
        boolean hasNo = seen.containsKey("No");
        if (!hasNo && !headerRow.isEmpty()) {
            TextBox first = headerRow.get(0);
            if (first.text.toLowerCase().contains("no")) {
                TableColumn noCol = new TableColumn("No", first.x, first.right());
                columns.add(0, noCol);
            }
        }

        return columns;
    }

    /**
     * Parse data rows between headerIdx+1 and endIdx (exclusive).
     * Each row's boxes are assigned to the nearest column by X center distance.
     */
    private static List<TableRow> parseDataRows(List<List<TextBox>> rows, int headerIdx, int endIdx,
                                                  List<TableColumn> columns) {
        List<TableRow> result = new ArrayList<>();
        if (headerIdx < 0 || columns.isEmpty()) return result;

        for (int i = headerIdx + 1; i < endIdx; i++) {
            List<TextBox> row = rows.get(i);
            if (row.isEmpty()) continue;

            TableRow tableRow = new TableRow();

            // Check for row number in the first box
            TextBox first = row.get(0);
            if (PATTERN_ROW_NUMBER.matcher(first.text.trim()).matches()) {
                try {
                    tableRow.rowNumber = Integer.parseInt(first.text.trim());
                } catch (NumberFormatException ignored) {}
            }

            // Skip if no row number and we haven't found any data yet
            if (tableRow.rowNumber < 0 && result.isEmpty()) continue;

            // Detect another header row (table boundary)
            if (tableRow.rowNumber < 0 && !result.isEmpty()) {
                String rowText = joinRowText(row).toLowerCase();
                int labelCount = 0;
                for (String kw : new String[]{"nik", "nama", "kelamin", "agama", "perkawinan", "hubungan", "ayah", "ibu"}) {
                    if (rowText.contains(kw)) labelCount++;
                }
                if (labelCount >= 3) break;
            }

            // Assign each box to nearest column
            for (TextBox box : row) {
                TableColumn bestCol = findNearestColumn(columns, box.cx);
                if (bestCol != null) {
                    String existing = tableRow.values.get(bestCol.header);
                    if (existing != null && !existing.isEmpty()) {
                        tableRow.values.put(bestCol.header, existing + " " + box.text);
                    } else {
                        tableRow.values.put(bestCol.header, box.text);
                    }
                }
            }

            if (tableRow.rowNumber > 0 || !tableRow.values.isEmpty()) {
                result.add(tableRow);
            }
        }
        return result;
    }

    private static TableColumn findNearestColumn(List<TableColumn> columns, float x) {
        TableColumn best = null;
        float minDist = Float.MAX_VALUE;
        for (TableColumn col : columns) {
            float dist = Math.abs(col.xCenter - x);
            if (dist < minDist) {
                minDist = dist;
                best = col;
            }
        }
        return best;
    }

    // ---- table merging ----

    private static JSONArray mergeTablesToMembers(List<TableRow> table1, List<TableRow> table2,
                                                   List<TextBox> allBoxes) throws Exception {
        JSONArray members = new JSONArray();

        Map<Integer, TableRow> table2Map = new HashMap<>();
        for (TableRow row : table2) {
            if (row.rowNumber > 0) table2Map.put(row.rowNumber, row);
        }

        for (TableRow row1 : table1) {
            JSONObject member = new JSONObject();

            String nik = getValueOrPattern(row1.values, new String[]{"NIK"}, PATTERN_NIK);
            String nama = getValue(row1.values, new String[]{"Nama"});
            nama = nama.replaceAll("^\\d+\\s*", "").trim();

            member.put("nik", nik);
            member.put("nama", nama);
            member.put("jenis_kelamin", normalizeGender(getValue(row1.values, new String[]{"Kelamin", "Jenis Kelamin"})));
            member.put("tempat_lahir", getValue(row1.values, new String[]{"Tempat"}));
            member.put("tanggal_lahir", getValueOrPattern(row1.values, new String[]{"Lahir", "Tanggal"}, PATTERN_DATE));
            member.put("agama", normalizeReligion(getValue(row1.values, new String[]{"Agama"})));
            member.put("pendidikan", getValue(row1.values, new String[]{"Pendidikan"}));
            member.put("pekerjaan", getValue(row1.values, new String[]{"Pekerjaan"}));

            TableRow row2 = (row1.rowNumber > 0) ? table2Map.get(row1.rowNumber) : null;
            if (row2 != null) {
                member.put("status_perkawinan", normalizeMaritalStatus(getValue(row2.values, new String[]{"Perkawinan"})));
                member.put("hubungan_keluarga", normalizeRelation(getValue(row2.values, new String[]{"Hubungan"})));
                member.put("kewarganegaraan", normalizeCitizenship(getValue(row2.values, new String[]{"Kewarganegaraan"})));
                member.put("nama_ayah", getValue(row2.values, new String[]{"Ayah"}));
                member.put("nama_ibu", getValue(row2.values, new String[]{"Ibu"}));
            } else {
                member.put("status_perkawinan", "");
                member.put("hubungan_keluarga", "");
                member.put("kewarganegaraan", "");
                member.put("nama_ayah", "");
                member.put("nama_ibu", "");
            }

            if (!nik.isEmpty() || !nama.isEmpty()) {
                members.put(member);
            }
        }

        // Fallback if table parsing found nothing
        if (members.length() == 0) {
            return fallbackExtraction(allBoxes);
        }
        return members;
    }

    // ---- value helpers ----

    private static String getValue(Map<String, String> values, String[] keys) {
        for (String key : keys) {
            String val = values.get(key);
            if (val != null && !val.isEmpty()) return val.trim();
        }
        return "";
    }

    private static String getValueOrPattern(Map<String, String> values, String[] keys, Pattern pattern) {
        String val = getValue(values, keys);
        if (!val.isEmpty()) {
            Matcher m = pattern.matcher(val);
            if (m.find()) return m.group();
            return val;
        }
        return "";
    }

    // ---- normalizers ----

    private static String normalizeGender(String text) {
        if (text.isEmpty()) return text;
        String upper = text.toUpperCase();
        if (upper.contains("LAKI")) return "LAKI-LAKI";
        if (upper.contains("PEREMPUAN") || upper.equals("PR") || upper.equals("P")) return "PEREMPUAN";
        return text;
    }

    private static String normalizeReligion(String text) {
        if (text.isEmpty()) return text;
        String upper = text.toUpperCase();
        for (String r : new String[]{"ISLAM", "KRISTEN", "KATOLIK", "HINDU", "BUDHA", "BUDDHA", "KONGHUCU"}) {
            if (upper.contains(r)) return r;
        }
        return text;
    }

    private static String normalizeMaritalStatus(String text) {
        if (text.isEmpty()) return text;
        String upper = text.toUpperCase();
        if (upper.contains("BELUM")) return "BELUM KAWIN";
        if (upper.contains("CERAI HIDUP")) return "CERAI HIDUP";
        if (upper.contains("CERAI MATI")) return "CERAI MATI";
        if (upper.contains("KAWIN")) return "KAWIN";
        return text;
    }

    private static String normalizeRelation(String text) {
        if (text.isEmpty()) return text;
        String upper = text.toUpperCase();
        if (upper.contains("KEPALA")) return "KEPALA KELUARGA";
        if (upper.contains("ISTRI")) return "ISTRI";
        if (upper.contains("ANAK")) return "ANAK";
        if (upper.contains("MENANTU")) return "MENANTU";
        if (upper.contains("CUCU")) return "CUCU";
        if (upper.contains("ORANG TUA")) return "ORANG TUA";
        if (upper.contains("MERTUA")) return "MERTUA";
        if (upper.contains("FAMILI")) return "FAMILI LAIN";
        return text;
    }

    private static String normalizeCitizenship(String text) {
        if (text.isEmpty()) return text;
        String upper = text.toUpperCase();
        if (upper.contains("WNI")) return "WNI";
        if (upper.contains("WNA")) return "WNA";
        return text;
    }

    // ---- fallback ----

    private static JSONArray fallbackExtraction(List<TextBox> boxes) throws Exception {
        JSONArray members = new JSONArray();

        List<TextBox> nikBoxes = new ArrayList<>();
        List<TextBox> dateBoxes = new ArrayList<>();
        List<TextBox> nameBoxes = new ArrayList<>();

        for (TextBox box : boxes) {
            if (PATTERN_NIK.matcher(box.text).find()) nikBoxes.add(box);
            if (PATTERN_DATE.matcher(box.text).find()) dateBoxes.add(box);
            if (box.text.matches("^[A-Z][A-Z\\s.]+$") && box.text.length() > 3 && !isLabelText(box.text)) {
                nameBoxes.add(box);
            }
        }

        // Skip first NIK (KK number)
        for (int i = 1; i < nikBoxes.size(); i++) {
            TextBox nikBox = nikBoxes.get(i);
            JSONObject member = new JSONObject();

            Matcher m = PATTERN_NIK.matcher(nikBox.text);
            member.put("nik", m.find() ? m.group() : "");
            member.put("nama", findNearestOnRow(nameBoxes, nikBox.cy, nikBox.h));
            member.put("tanggal_lahir", findNearestDateOnRow(dateBoxes, nikBox.cy, nikBox.h));
            member.put("jenis_kelamin", "");
            member.put("tempat_lahir", "");
            member.put("agama", "");
            member.put("pendidikan", "");
            member.put("pekerjaan", "");
            member.put("status_perkawinan", "");
            member.put("hubungan_keluarga", "");
            member.put("kewarganegaraan", "");
            member.put("nama_ayah", "");
            member.put("nama_ibu", "");

            members.put(member);
        }
        return members;
    }

    private static String findNearestOnRow(List<TextBox> boxes, float targetY, float rowHeight) {
        for (TextBox box : boxes) {
            if (Math.abs(box.cy - targetY) < rowHeight * 1.5f) return box.text;
        }
        return "";
    }

    private static String findNearestDateOnRow(List<TextBox> boxes, float targetY, float rowHeight) {
        for (TextBox box : boxes) {
            if (Math.abs(box.cy - targetY) < rowHeight * 1.5f) {
                Matcher m = PATTERN_DATE.matcher(box.text);
                if (m.find()) return m.group();
            }
        }
        return "";
    }

    private static boolean isLabelText(String text) {
        String upper = text.toUpperCase().trim();
        for (String label : new String[]{
            "NIK", "NAMA", "TEMPAT", "TANGGAL", "LAHIR", "AGAMA", "PENDIDIKAN", "PEKERJAAN",
            "STATUS", "HUBUNGAN", "KEWARGANEGARAAN", "AYAH", "IBU", "ALAMAT",
            "DESA", "KELURAHAN", "KECAMATAN", "KABUPATEN", "KOTA", "PROVINSI",
            "NO", "RT", "RW", "KODE", "KARTU", "KELUARGA", "JENIS", "KELAMIN",
            "PERKAWINAN", "DOKUMEN", "IMIGRASI", "LENGKAP", "POS"
        }) {
            if (upper.equals(label) || upper.contains(label + " ") || upper.contains(" " + label)) {
                return true;
            }
        }
        return false;
    }
}
