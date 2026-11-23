package com.medcollect;

import static com.medcollect.BusinessCatalog.Option;
import static com.medcollect.Support.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

@Service
class BusinessDictionaries {
    private final CdssClient cdss;
    private final KnowledgeTemplates templates;
    private final BusinessCatalog catalog;
    BusinessDictionaries(CdssClient cdss,KnowledgeTemplates templates,BusinessCatalog catalog) {
        this.cdss=cdss; this.templates=templates; this.catalog=catalog;
    }
    private record Directory(String endpoint,String find,String label,String code,String value) {}
    private static Directory medical(String path) {
        String base="/knowledge-dict/"+path;
        return new Directory(base+"/query",base+"/find-by-id","name","code","id");
    }
    private static final Map<String,Directory> DIRECTORIES=Map.of(
            "icd10Ids",medical("icd10"), "drugCodes",medical("drug"), "diseaseIds",medical("disease"),
            "operationIds",medical("operation"),"treatmentIds",medical("treatment"),"exam",medical("exam"),
            "lab",new Directory("/knowledge-dict/lab/query-labItem","/knowledge-dict/lab/find-labItem-by-id","name","code","id"),
            "deptCode",new Directory("/dept/query",null,"deptName","deptCode","deptCode"),
            "ruleIds",new Directory("/rule/query-rule-list","/rule/find-rule-by-id","ruleName","ruleCode","id"));

    private Directory directory(String type,String key) {
        if (!templates.category(type).relations().contains(key)) throw bad("该模板没有此关联字段");
        String name=key.equals("itemIds") ? (type.equals("8-2") ? "exam":"lab") : key;
        return DIRECTORIES.get(name);
    }
    List<Option> parameters(String name,boolean fresh) {
        var snapshot=catalog.current();
        if(snapshot==null || !snapshot.dictionaries().containsKey(name)) throw bad("该参数字典尚未从 4.23 同步");
        return fresh ? catalog.readParameterOptions(name) : snapshot.dictionaries().get(name);
    }
    private String parameterName(String key) {
        return switch(key) { case "countyUseId" -> "COUNTY_USE_DICT"; case "nurseIds" -> "NURSE_CLASS_DICT"; default -> null; };
    }

    Records.Page<Option> search(String type,String key,String term,int page,int size) {
        if(page<1 || page>1000 || size<1 || size>50) throw bad("字典页码或页大小超出范围");
        Directory d=directory(type,key);
        String q=trim(term);
        if(d==null) {
            var rows=parameters(parameterName(key),false).stream().filter(o->o.name().contains(q) || o.code().contains(q)).toList();
            return slice(rows,page,size);
        }
        if(key.equals("ruleIds") && q.isBlank()) return new Records.Page<>(List.of(),0,page,size);
        Map<String,String> fields=filters(key);
        boolean isCode=!q.isBlank() && q.matches("[A-Za-z0-9][A-Za-z0-9.\\-]*") && !key.equals("ruleIds");
        if(!q.isBlank()) fields.put(isCode?d.code():d.label(),q);
        fields.put("page",String.valueOf(page)); fields.put("rows",String.valueOf(size));
        var envelope=cdss.reference(d.endpoint(),fields);
        var source=BusinessCatalog.array(envelope.path("data"),"关联字典");
        var rows=new ArrayList<Option>();
        for(var row:source) {
            var o=option(d,row);
            if(o!=null) rows.add(o);
        }
        // The native rule-name endpoint returns the complete name match, without page metadata.
        if(key.equals("ruleIds")) return slice(rows,page,size);
        long total=envelope.path("total").asLong(envelope.path("count").asLong((long)(page-1)*size+rows.size()));
        return new Records.Page<>(rows,total,page,size);
    }

    private Map<String,String> filters(String key) {
        var fields=new LinkedHashMap<String,String>();
        fields.put("validFlag","1");
        if(key.equals("icd10Ids")) fields.put("source","国家临床版2.0");
        if(key.equals("deptCode")) { fields.put("orgId","0"); fields.put("deptType","12"); }
        return fields;
    }
    private Option option(Directory d,JsonNode row) {
        String value=row.path(d.value()).asText(""), name=row.path(d.label()).asText("");
        if(value.isBlank() || name.isBlank() || row.path("validFlag").asInt(1)!=1) return null;
        return new Option(value,name,row.path(d.code()).asText(""),row.path("source").asText(""));
    }
    private Records.Page<Option> slice(List<Option> rows,int page,int size) {
        int start=Math.min((page-1)*size,rows.size());
        return new Records.Page<>(rows.subList(start,Math.min(start+size,rows.size())),rows.size(),page,size);
    }

    List<Option> resolve(String type,String key,List<String> values,boolean fresh) {
        Directory d=directory(type,key);
        if(values.size()>50 || values.stream().anyMatch(v->trim(v).isBlank() || v.length()>80)) throw bad("无效的字典选择");
        if(values.isEmpty()) return List.of();
        if(d==null) return parameters(parameterName(key),fresh).stream().filter(o->values.contains(o.value())).toList();
        var result=new ArrayList<Option>();
        for(var value:new LinkedHashSet<>(values)) {
            if(d.find()==null) {
                var fields=filters(key);
                fields.put("deptCode",value); fields.put("page","1"); fields.put("rows","50");
                for(var row:BusinessCatalog.array(cdss.reference(d.endpoint(),fields).path("data"),"科室")) {
                    var item=option(d,row);
                    if(item!=null && value.equals(item.value())) { result.add(item); break; }
                }
            } else {
                var row=cdss.reference(d.find(),Map.of("id",value)).path("data");
                var item=option(d,row);
                if(item!=null && value.equals(item.value()) && (!key.equals("icd10Ids")
                        || "国家临床版2.0".equals(row.path("source").asText()))) result.add(item);
            }
        }
        return result;
    }
    String dictionaryOf(KnowledgeTemplates.Schema schema, KnowledgeTemplates.Field field) {
        if(!trim(field.dictName()).isBlank()) return field.dictName();
        for(var node:schema.layout().path("properties")) if(field.id().equals(node.path("id").asText())) return node.path("dictName").asText("");
        return "";
    }
    void validate(KnowledgeTemplates.Schema schema,Map<String,String> fields,Map<String,List<String>> links,boolean fresh) {
        for(var entry:links.entrySet()) if(!entry.getValue().isEmpty() &&
                resolve(schema.category().code(),entry.getKey(),entry.getValue(),fresh).size()!=new HashSet<>(entry.getValue()).size())
            throw bad("所选业务字典不存在或已停用，请重新选择");
        Map<String,List<Option>> loaded=new HashMap<>();
        for(var field:schema.fields()) {
            String value=trim(fields.get(field.id()));
            if(!field.dictionary() || value.isBlank()) continue;
            String name=dictionaryOf(schema,field);
            if(name.isBlank() || loaded.computeIfAbsent(name,k->parameters(k,fresh)).stream().noneMatch(o->o.value().equals(value)))
                throw bad(field.label()+"的编码已失效，请从 4.23 字典重新选择");
        }
    }
}
