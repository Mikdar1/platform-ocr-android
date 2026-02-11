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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RuleBasedExtractor {

    // Patterns for Indonesian Kartu Keluarga
    private static final Pattern PATTERN_KK = Pattern.compile("\\b(\\d{16})\\b");
    private static final Pattern PATTERN_NIK = Pattern.compile("\\b(\\d{16})\\b");
    private static final Pattern PATTERN_DATE = Pattern.compile("\\b(\\d{2}[-/]\\d{2}[-/]\\d{4})\\b");
    private static final Pattern PATTERN_RT_RW = Pattern.compile("\\b(\\d{3})[/](\\d{3})\\b");

    // Known labels in Kartu Keluarga
    private static final String[] GENDER_MALE = {"LAKI-LAKI", "LAKI", "L"};
    private static final String[] GENDER_FEMALE = {"PEREMPUAN", "P"};
    private static final String[] RELIGIONS = {"ISLAM", "KRISTEN", "KATOLIK", "HINDU", "BUDHA", "KONGHUCU"};
    private static final String[] CITIZENSHIPS = {"WNI", "WNA"};
    private static final String[] MARITAL_STATUS = {"BELUM KAWIN", "KAWIN", "CERAI HIDUP", "CERAI MATI"};
    private static final String[] RELATIONS = {"KEPALA KELUARGA", "ISTRI", "ANAK", "MENANTU", "CUCU", "ORANG TUA", "MERTUA", "FAMILI LAIN", "PEMBANTU", "LAINNYA"};
    private static final String[] EDUCATIONS = {"TIDAK/BELUM SEKOLAH", "BELUM TAMAT SD/SEDERAJAT", "TAMAT SD/SEDERAJAT", "SLTP/SEDERAJAT", "SLTA/SEDERAJAT", "DIPLOMA I/II", "AKADEMI/DIPLOMA III/S.MUDA", "DIPLOMA IV/STRATA I", "STRATA II", "STRATA III"};

    public static String extract(String ocrText) {
        try {
            JSONObject result = new JSONObject();

            // Split OCR text into lines
            String[] lines = ocrText.split("\n");
            List<String> cleanLines = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    cleanLines.add(trimmed);
                }
            }

            // Extract 16-digit numbers (KK and NIK)
            List<String> allNumbers = new ArrayList<>();
            Matcher numberMatcher = PATTERN_KK.matcher(ocrText);
            while (numberMatcher.find()) {
                String num = numberMatcher.group(1);
                if (!allNumbers.contains(num)) {
                    allNumbers.add(num);
                }
            }

            // First 16-digit number is likely KK number
            if (allNumbers.size() > 0) {
                result.put("no_kk", allNumbers.get(0));
            } else {
                result.put("no_kk", "");
            }

            // Extract RT/RW
            Matcher rtRwMatcher = PATTERN_RT_RW.matcher(ocrText);
            if (rtRwMatcher.find()) {
                result.put("rt_rw", rtRwMatcher.group(0));
            } else {
                result.put("rt_rw", "");
            }

            // Extract dates
            List<String> dates = new ArrayList<>();
            Matcher dateMatcher = PATTERN_DATE.matcher(ocrText);
            while (dateMatcher.find()) {
                dates.add(dateMatcher.group(1));
            }

            // Find kepala keluarga - look for text after "Nama Kepala Keluarga" or similar
            String kepalaKeluarga = findValueAfterLabel(cleanLines, new String[]{"Nama Kepala Keluarga", "KEPALA KELUARGA", "Kepala Keluarga"});
            result.put("kepala_keluarga", kepalaKeluarga);

            // Find alamat
            String alamat = findValueAfterLabel(cleanLines, new String[]{"Alamat", "ALAMAT"});
            result.put("alamat", alamat);

            // Find desa/kelurahan
            String desa = findValueAfterLabel(cleanLines, new String[]{"Desa/Kelurahan", "Desa", "Kelurahan", "DESA", "KELURAHAN"});
            result.put("desa_kelurahan", desa);

            // Find kecamatan
            String kecamatan = findValueAfterLabel(cleanLines, new String[]{"Kecamatan", "KECAMATAN"});
            result.put("kecamatan", kecamatan);

            // Find kabupaten/kota
            String kabupaten = findValueAfterLabel(cleanLines, new String[]{"Kabupaten/Kota", "Kabupaten", "Kota", "KABUPATEN", "KOTA"});
            result.put("kabupaten_kota", kabupaten);

            // Find provinsi
            String provinsi = findValueAfterLabel(cleanLines, new String[]{"Provinsi", "PROVINSI"});
            result.put("provinsi", provinsi);

            // Extract family members
            JSONArray anggotaKeluarga = new JSONArray();

            // Find all names (lines that are all uppercase and contain letters)
            List<String> potentialNames = new ArrayList<>();
            for (String line : cleanLines) {
                // Names are usually all uppercase, contain only letters and spaces
                if (line.matches("^[A-Z\\s]+$") && line.length() > 3 && !isKnownLabel(line)) {
                    potentialNames.add(line);
                }
            }

            // Create family member entries
            // NIKs after the first one belong to family members
            for (int i = 0; i < Math.max(potentialNames.size(), allNumbers.size() - 1); i++) {
                JSONObject member = new JSONObject();

                // NIK (skip first which is KK)
                if (i + 1 < allNumbers.size()) {
                    member.put("nik", allNumbers.get(i + 1));
                } else {
                    member.put("nik", "");
                }

                // Name
                if (i < potentialNames.size()) {
                    member.put("nama", potentialNames.get(i));
                } else {
                    member.put("nama", "");
                }

                // Find gender in OCR text
                member.put("jenis_kelamin", findInText(ocrText, GENDER_MALE, "L", GENDER_FEMALE, "P"));

                // Birth date
                if (i < dates.size()) {
                    member.put("tanggal_lahir", dates.get(i));
                } else {
                    member.put("tanggal_lahir", "");
                }

                // Find religion
                member.put("agama", findMatchInText(ocrText, RELIGIONS));

                // Find citizenship
                member.put("kewarganegaraan", findMatchInText(ocrText, CITIZENSHIPS));

                // Find marital status
                member.put("status_perkawinan", findMatchInText(ocrText, MARITAL_STATUS));

                // Find relation
                member.put("hubungan_keluarga", findMatchInText(ocrText, RELATIONS));

                // Placeholders for fields that need more context
                member.put("tempat_lahir", "");
                member.put("pendidikan", findMatchInText(ocrText, EDUCATIONS));
                member.put("pekerjaan", "");
                member.put("nama_ayah", "");
                member.put("nama_ibu", "");

                if (member.getString("nik").length() > 0 || member.getString("nama").length() > 0) {
                    anggotaKeluarga.put(member);
                }
            }

            result.put("anggota_keluarga", anggotaKeluarga);

            return result.toString(2);

        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private static String findValueAfterLabel(List<String> lines, String[] labels) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String label : labels) {
                if (line.toLowerCase().contains(label.toLowerCase())) {
                    // Check if value is on same line after colon
                    int colonIndex = line.indexOf(':');
                    if (colonIndex >= 0 && colonIndex < line.length() - 1) {
                        return line.substring(colonIndex + 1).trim();
                    }
                    // Check next line
                    if (i + 1 < lines.size()) {
                        String nextLine = lines.get(i + 1);
                        if (!isKnownLabel(nextLine)) {
                            return nextLine;
                        }
                    }
                }
            }
        }
        return "";
    }

    private static boolean isKnownLabel(String text) {
        String[] labels = {"NIK", "Nama", "Tempat", "Tanggal", "Agama", "Pendidikan", "Pekerjaan",
                          "Status", "Hubungan", "Kewarganegaraan", "Ayah", "Ibu", "Alamat",
                          "Desa", "Kelurahan", "Kecamatan", "Kabupaten", "Kota", "Provinsi",
                          "No.", "RT", "RW", "Kode"};
        for (String label : labels) {
            if (text.toLowerCase().contains(label.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String findMatchInText(String text, String[] patterns) {
        String upperText = text.toUpperCase();
        for (String pattern : patterns) {
            if (upperText.contains(pattern.toUpperCase())) {
                return pattern;
            }
        }
        return "";
    }

    private static String findInText(String text, String[] patterns1, String result1, String[] patterns2, String result2) {
        String upperText = text.toUpperCase();
        for (String pattern : patterns1) {
            if (upperText.contains(pattern.toUpperCase())) {
                return result1;
            }
        }
        for (String pattern : patterns2) {
            if (upperText.contains(pattern.toUpperCase())) {
                return result2;
            }
        }
        return "";
    }
}
