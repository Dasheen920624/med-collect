package com.medcollect;

import static com.medcollect.Support.*;

import java.sql.Types;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class BusinessCatalog {
    record Kind(String code, String name) {}
    record Entry(String code, String name, String parent) {}
    record Option(String value, String name, String code, String source) {}
    record Snapshot(long syncedAt, List<Kind> kinds, List<Entry> tree, JsonNode templates,
                    Map<String,List<Option>> dictionaries) {}
    record Status(boolean configured, long syncedAt, int types, int specialties, int diseases,
                  int templateCount, int dictionaryCount, String error) {}

    private static final String KEY = "business_reference_v1";
    private final CdssClient cdss;
    private final Store store;
    private final Classifications classifications;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final AppSettings settings;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile Snapshot cached;
    private volatile long nextRead;
    private volatile String failure = "";

    BusinessCatalog(CdssClient cdss, Store store, Classifications classifications, TransactionTemplate tx,
                    ObjectMapper json, AppSettings settings) {
        this.cdss=cdss; this.store=store; this.classifications=classifications; this.tx=tx;
        this.json=json; this.settings=settings;
    }

    Snapshot current() {
        if (store.now() >= nextRead) {
            synchronized (this) {
                if (store.now() >= nextRead) {
                    String raw=store.db().sql("SELECT setting_value FROM mc_settings WHERE setting_key=:key")
                            .param("key",KEY).query(String.class).single();
                    try { cached = raw.equals("{}") ? null : json.readValue(raw,Snapshot.class); }
                    catch (java.io.IOException e) { throw new IllegalStateException("业务目录快照不可读取",e); }
                    nextRead = store.now()+30000;
                }
            }
        }
        return cached;
    }

    Status status() {
        var s=current();
        if (s==null) return new Status(cdss.configured(),0,0,0,0,0,0,failure);
        var specialties=s.tree().stream().filter(n->"17".equals(n.parent())).map(Entry::code)
                .collect(java.util.stream.Collectors.toSet());
        int diseases=(int)s.tree().stream().filter(n->specialties.contains(n.parent())).count();
        return new Status(cdss.configured(),s.syncedAt(),s.kinds().size(),specialties.size(),diseases,
                s.templates().size(),s.dictionaries().size(),failure);
    }

    @Scheduled(initialDelayString="${medcollect.reference-initial-delay-ms:45000}",fixedDelayString="${medcollect.reference-interval-ms:21600000}")
    void scheduled() {
        if (!settings.schedulingEnabled() || !cdss.configured()) return;
        try {
            var existing=current();
            if (existing==null || store.now()-existing.syncedAt()>=TimeUnit.HOURS.toMillis(5)) refresh("system");
        } catch (RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Business catalog refresh failed: {}", e.getMessage()); }
    }

    Status refresh(String actor) {
        if (!cdss.configured()) throw bad("请先配置业务系统连接");
        if (!refreshLock.tryLock()) throw conflict("业务资料正在同步，请稍后刷新");
        try {
            long started=store.now();
            var kinds=new LinkedHashMap<String,Kind>();
            for (var row : array(cdss.verifyConnection(),"知识入口")) {
                String key=row.path("childType").asText("");
                if (key.isBlank()) key=row.path("itemClass").asText("");
                String name=row.path("itemName").asText("");
                if (!key.isBlank() && !name.isBlank()) kinds.putIfAbsent(key,new Kind(key,name));
            }
            var entries=new LinkedHashMap<String,Entry>();
            for (var row : array(cdss.classes(""),"知识类别")) {
                String key=row.path("knowledgeClass").asText("");
                String name=row.path("knowledgeClassName").asText("");
                if (!key.isBlank() && !name.isBlank()) entries.putIfAbsent(key,new Entry(key,name,row.path("upperClass").asText("")));
            }
            JsonNode templates=cdss.templates();
            if (kinds.isEmpty() || entries.isEmpty() || !templates.isObject() || templates.isEmpty())
                throw new CdssClient.RemoteFailure("4.23 的知识入口、类别或模板为空，保留上次同步资料");
            Set<String> dictNames=new TreeSet<>(List.of("COUNTY_USE_DICT","NURSE_CLASS_DICT"));
            templates.findValuesAsText("dictName").forEach(n->{if(n.matches("[A-Za-z0-9_]{1,80}")) dictNames.add(n);});
            var dictionaries=new LinkedHashMap<String,List<Option>>();
            for(String name:dictNames) dictionaries.put(name,readParameterOptions(name));
            var fresh=new Snapshot(started,List.copyOf(kinds.values()),List.copyOf(entries.values()),templates,dictionaries);
            tx.executeWithoutResult(ignored->{
                classifications.lock();
                String previous=store.db().sql("SELECT setting_value FROM mc_settings WHERE setting_key=:key FOR UPDATE")
                        .param("key",KEY).query(String.class).single();
                try {
                    if(json.readTree(previous).path("syncedAt").asLong()>started) return;
                    new CatalogDirectories(store,classifications.nodes()).apply(fresh);
                    store.db().sql("UPDATE mc_settings SET setting_value=:value WHERE setting_key=:key")
                            .param("key",KEY).param("value",json.writeValueAsString(fresh),Types.CLOB).update();
                } catch(java.io.IOException e) { throw new IllegalStateException(e); }
                store.event("BUSINESS_CATALOG",KEY,"REFRESHED",actor,"业务入口 "+kinds.size()+"，知识分类 "+entries.size());
            });
            synchronized(this) { nextRead=0; }
            failure="";
            return status();
        } catch(RuntimeException e) {
            failure=e instanceof CdssClient.RemoteFailure ? e.getMessage() : "同步未完成，请核对服务日志";
            throw e;
        } finally { refreshLock.unlock(); }
    }

    List<Option> readParameterOptions(String name) {
        List<Option> result=new ArrayList<>();
        int size=200;
        for(int page=1; page<=100; page++) {
            var req=Map.of("dictName",name,"dictNameEq","1","validFlag","1","page",""+page,"rows",""+size);
            var rows=array(cdss.reference("/dict/findMapPage",req).path("data"),name);
            for(var row:rows) {
                var code=row.path("code").asText("");
                var label=row.path("name").asText("");
                if (!code.isBlank() && !label.isBlank() && row.path("validFlag").asInt(1)==1) {
                    String id=row.path("id").asText("");
                    result.add(new Option(name.equals("COUNTY_USE_DICT")?id:code,label,code,name));
                }
            }
            if(rows.size()<size) return List.copyOf(result);
        }
        throw new CdssClient.RemoteFailure("字典分页未结束，请检查 4.23 的字典接口");
    }

    static JsonNode array(JsonNode node,String label) {
        if(!node.isArray()) throw new CdssClient.RemoteFailure("4.23 返回的"+label+"列表格式不正确");
        return node;
    }
}
