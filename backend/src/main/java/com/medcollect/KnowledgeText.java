package com.medcollect;

import static com.medcollect.Support.trim;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Pattern;

/** Separates explicit bibliographic labels from medical text without editing the archived original. */
final class KnowledgeText {
    static final String SOURCE = "laiyuan";
    record Separated(String summary, Map<String,String> fields) {}
    private static final Pattern TITLE_PREFIX = Pattern.compile(
            "^(?:据\\h*)?([^《》\\s。，：]{0,65})《([^》]{1,240})》\\h*(?:记载|指出|介绍|说明)?\\h*[，,。:：]\\s*");
    private static final Pattern FIELD_PREFIX = Pattern.compile(
            "^(?:据\\h*)?([^\\s。，：]{2,65}?)(?:项目说明|说明)(?:\\h*\\n\\s*|[，,:：]\\h*)");
    private KnowledgeText() {}

    static Separated separate(String summary, Map<String,String> fields, Records.Material material) {
        var notes = new LinkedHashSet<String>();
        var clean = new LinkedHashMap<>(fields);
        trim(fields.get(SOURCE)).lines().filter(line -> !line.isBlank()).forEach(notes::add);
        var paragraphs = new java.util.ArrayList<String>();
        for (String paragraph : trim(summary).replace("\r\n","\n").split("\\n\\h*\\n")) {
            String s = paragraph.strip();
            if (s.startsWith("补充出处：") || s.startsWith("补充出处:")
                    || (s.matches("(?is)^[^。!?！？]{1,80}发表于[^。!?！？]{1,130}[。.]\\s*DOI[\\s:：].*")
                        && !BusinessKnowledge.firstDoi(s).isEmpty() && s.length()<450)) {
                notes.add(s);
                continue;
            }
            var prefix = TITLE_PREFIX.matcher(s);
            if (prefix.find() && (publisher(prefix.group(1), material) || relatedTitle(prefix.group(2), material))) {
                String citation = prefix.group(1) + "《" + prefix.group(2) + "》";
                notes.add(citation);
                s = s.substring(prefix.end()).stripLeading();
                String repeated = prefix.group(1) + "发布的" + prefix.group(2) + "，";
                if (s.startsWith(repeated)) s = s.substring(repeated.length());
            }
            if (!s.isEmpty()) paragraphs.add(s);
        }
        fields.forEach((key,value) -> {
            if (SOURCE.equals(key) || "references".equals(key) || value == null) return;
            var prefix = FIELD_PREFIX.matcher(value.stripLeading());
            if (prefix.find() && publisher(prefix.group(1),material)) {
                notes.add(prefix.group(1) + "项目说明");
                clean.put(key, value.stripLeading().substring(prefix.end()).strip());
            }
        });
        if (!notes.isEmpty()) clean.put(SOURCE, String.join("\n",notes));
        return new Separated(String.join("\n\n",paragraphs),clean);
    }

    private static boolean relatedTitle(String title, Records.Material m) {
        return !title.isBlank() && trim(m.title()).contains(title);
    }
    private static boolean publisher(String name, Records.Material m) {
        return !name.isBlank() && (name.equals(m.publisher()) || name.equals(m.sourceName()) || trim(m.title()).contains(name));
    }

    static String citation(Records.Material m) {
        var parts = new LinkedHashSet<String>();
        for (String s : new String[]{m.authors(), "《" + m.title() + "》", m.publisher(), m.published(), m.externalRef()}) {
            if (!trim(s).isEmpty() && !"作者".equals(s)) parts.add(s.strip());
        }
        if (trim(m.publisher()).isEmpty() && !trim(m.sourceName()).isEmpty()) parts.add(m.sourceName());
        String doi = BusinessKnowledge.firstDoi(m.externalRef(), m.sourceUrl(), m.fetchedUrl());
        if (!doi.isEmpty() && BusinessKnowledge.firstDoi(m.externalRef()).isEmpty()) parts.add("DOI " + doi);
        return String.join(" · ", parts);
    }
}
