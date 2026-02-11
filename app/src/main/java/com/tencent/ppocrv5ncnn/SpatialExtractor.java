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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpatialExtractor {

    private static final Pattern PATTERN_16_DIGITS = Pattern.compile("\\d{16}");
    private static final Pattern PATTERN_DATE = Pattern.compile("\\d{2}[-/]\\d{2}[-/]\\d{4}");
    private static final Pattern PATTERN_RT_RW = Pattern.compile("\\d{3}/\\d{3}");

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

        float bottom() { return y + h; }
        float right() { return x + w; }
    }

    public static String extract(String ocrJsonWithBoxes) {
        try {
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

            // Sort by Y then X (reading order: top to bottom, left to right)
            Collections.sort(textBoxes, new Comparator<TextBox>() {
                @Override
                public int compare(TextBox a, TextBox b) {
                    // Group by rows (similar Y within threshold)
                    float yThreshold = 15;
                    if (Math.abs(a.cy - b.cy) < yThreshold) {
                        return Float.compare(a.cx, b.cx);
                    }
                    return Float.compare(a.cy, b.cy);
                }
            });

            JSONObject result = new JSONObject();

            // Extract header fields
            result.put("no_kk", findValueAfterLabel(textBoxes, "No."));
            result.put("kepala_keluarga", findValueAfterLabel(textBoxes, "Nama Kepala Keluarga"));
            result.put("alamat", findValueAfterLabel(textBoxes, "Alamat"));
            result.put("rt_rw", findPattern(textBoxes, PATTERN_RT_RW));
            result.put("desa_kelurahan", findValueAfterLabel(textBoxes, "Desa/Kelurahan"));
            result.put("kecamatan", findValueAfterLabel(textBoxes, "Kecamatan"));
            result.put("kabupaten_kota", findValueAfterLabel(textBoxes, "Kabupaten/Kota"));
            result.put("provinsi", findValueAfterLabel(textBoxes, "Provinsi"));

            // Group rows for table data
            List<List<TextBox>> tableRows = groupIntoRows(textBoxes);

            // Extract family members from table rows
            JSONArray members = new JSONArray();
            List<String> allNiks = findAllMatches(textBoxes, PATTERN_16_DIGITS);
            List<String> allDates = findAllMatches(textBoxes, PATTERN_DATE);
            List<String> allNames = findPotentialNames(textBoxes);

            // First NIK is KK number, rest are member NIKs
            if (allNiks.size() > 0) {
                result.put("no_kk", allNiks.get(0));
            }

            // Create member entries
            int nikIndex = 1; // Skip first (KK number)
            int dateIndex = 0;
            int nameIndex = 0;

            while (nikIndex < allNiks.size() || nameIndex < allNames.size()) {
                JSONObject member = new JSONObject();

                if (nikIndex < allNiks.size()) {
                    member.put("nik", allNiks.get(nikIndex));
                    nikIndex++;
                } else {
                    member.put("nik", "");
                }

                if (nameIndex < allNames.size()) {
                    member.put("nama", allNames.get(nameIndex));
                    nameIndex++;
                } else {
                    member.put("nama", "");
                }

                // Find associated data in same row
                member.put("jenis_kelamin", findInList(textBoxes, new String[]{"LAKI-LAKI", "PEREMPUAN"}));
                member.put("agama", findInList(textBoxes, new String[]{"ISLAM", "KRISTEN", "KATOLIK", "HINDU", "BUDHA", "KONGHUCU"}));
                member.put("kewarganegaraan", findInList(textBoxes, new String[]{"WNI", "WNA"}));
                member.put("status_perkawinan", findInList(textBoxes, new String[]{"BELUM KAWIN", "KAWIN", "KAWIN TERCATAT", "CERAI HIDUP", "CERAI MATI"}));
                member.put("hubungan_keluarga", findInList(textBoxes, new String[]{"KEPALA KELUARGA", "ISTRI", "ANAK", "MENANTU", "CUCU"}));

                if (dateIndex < allDates.size()) {
                    member.put("tanggal_lahir", allDates.get(dateIndex));
                    dateIndex++;
                } else {
                    member.put("tanggal_lahir", "");
                }

                member.put("tempat_lahir", "");
                member.put("pendidikan", "");
                member.put("pekerjaan", "");
                member.put("nama_ayah", "");
                member.put("nama_ibu", "");

                if (member.getString("nik").length() > 0 || member.getString("nama").length() > 0) {
                    members.put(member);
                }
            }

            result.put("anggota_keluarga", members);

            return result.toString(2);

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private static String findValueAfterLabel(List<TextBox> boxes, String label) {
        for (int i = 0; i < boxes.size(); i++) {
            TextBox box = boxes.get(i);
            if (box.text.toLowerCase().contains(label.toLowerCase())) {
                // Check if value is after colon on same line
                if (box.text.contains(":")) {
                    int colonIdx = box.text.indexOf(':');
                    if (colonIdx < box.text.length() - 1) {
                        return box.text.substring(colonIdx + 1).trim();
                    }
                }
                // Look for value to the right (same Y, higher X)
                for (int j = i + 1; j < boxes.size(); j++) {
                    TextBox next = boxes.get(j);
                    if (Math.abs(next.cy - box.cy) < 20 && next.cx > box.right()) {
                        if (!isLabel(next.text)) {
                            return next.text;
                        }
                    }
                }
            }
        }
        return "";
    }

    private static String findPattern(List<TextBox> boxes, Pattern pattern) {
        for (TextBox box : boxes) {
            Matcher m = pattern.matcher(box.text);
            if (m.find()) {
                return m.group();
            }
        }
        return "";
    }

    private static List<String> findAllMatches(List<TextBox> boxes, Pattern pattern) {
        List<String> matches = new ArrayList<>();
        for (TextBox box : boxes) {
            Matcher m = pattern.matcher(box.text);
            while (m.find()) {
                String match = m.group();
                if (!matches.contains(match)) {
                    matches.add(match);
                }
            }
        }
        return matches;
    }

    private static List<String> findPotentialNames(List<TextBox> boxes) {
        List<String> names = new ArrayList<>();
        for (TextBox box : boxes) {
            String text = box.text.trim();
            // Names are typically all uppercase, contain letters and spaces, length > 3
            if (text.matches("^[A-Z\\s]+$") && text.length() > 3 && !isLabel(text)) {
                names.add(text);
            }
        }
        return names;
    }

    private static String findInList(List<TextBox> boxes, String[] options) {
        for (TextBox box : boxes) {
            String upper = box.text.toUpperCase();
            for (String opt : options) {
                if (upper.contains(opt)) {
                    return opt;
                }
            }
        }
        return "";
    }

    private static boolean isLabel(String text) {
        String[] labels = {"NIK", "Nama", "Tempat", "Tanggal", "Agama", "Pendidikan", "Pekerjaan",
                          "Status", "Hubungan", "Kewarganegaraan", "Ayah", "Ibu", "Alamat",
                          "Desa", "Kelurahan", "Kecamatan", "Kabupaten", "Kota", "Provinsi",
                          "No.", "RT", "RW", "Kode", "KARTU KELUARGA", "Jenis", "Kelamin"};
        for (String label : labels) {
            if (text.toLowerCase().contains(label.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static List<List<TextBox>> groupIntoRows(List<TextBox> boxes) {
        List<List<TextBox>> rows = new ArrayList<>();
        float threshold = 15;

        List<TextBox> currentRow = new ArrayList<>();
        float currentY = -1000;

        for (TextBox box : boxes) {
            if (Math.abs(box.cy - currentY) > threshold) {
                if (!currentRow.isEmpty()) {
                    rows.add(currentRow);
                }
                currentRow = new ArrayList<>();
                currentY = box.cy;
            }
            currentRow.add(box);
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        return rows;
    }
}
