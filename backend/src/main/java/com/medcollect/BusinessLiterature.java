package com.medcollect;

import static com.medcollect.Support.*;
import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

@Service
class BusinessLiterature {
    record Literature(String id,String type,String title,String knowledgeClass,String source,String updated,String businessUrl) {}
    record Attachment(String id,String name) {}
    record Detail(Literature item,String summary,String publisherUrl,List<Attachment> attachments) {}
    private final CdssClient cdss;
    private final DocumentReader reader;
    private final ObjectMapper json;
    BusinessLiterature(CdssClient cdss,DocumentReader reader,ObjectMapper json) { this.cdss=cdss;this.reader=reader;this.json=json; }
    private boolean supported(String type) { return List.of("17","30","31").contains(type); }
    static String key(String input) {
        if(!trim(input).matches("[A-Za-z0-9_-]{1,80}")) throw bad("业务资料标识无效");
        return input;
    }
    Records.Page<Literature> search(String type,String q,String classCode,int page,int size) {
        if(!supported(type) || page<1 || page>1000 || size<1 || size>50) throw bad("请选择指南、书籍或期刊和有效页码");
        var fields=new LinkedHashMap<>(Map.of("type",type,"guideType",type));
        if(!trim(q).isEmpty()) fields.put("name",q.strip());
        if(!trim(classCode).isEmpty()) fields.put("knowledgeClass",classCode);
        var data=cdss.search(fields,page,size);
        var rows=new ArrayList<Literature>();
        for(var row:data.rows()) if(type.equals(row.path("type").asText())) rows.add(item(row));
        return new Records.Page<>(rows,data.total(),page,size);
    }
    private Literature item(JsonNode row) {
        String id=key(row.path("id").asText());
        return new Literature(id,row.path("type").asText(),row.path("name").asText(),row.path("knowledgeClass").asText(""),
                row.path("source").asText(""),row.path("modifyTime").asText(""),cdss.previewUrl(id));
    }
    private JsonNode row(String id) {
        var row=cdss.knowledge(key(id));
        if(!supported(row.path("type").asText()) || row.path("validFlag").asInt(1)!=1) throw bad("此记录不属于有效的指南、书籍或期刊");
        return row;
    }
    private JsonNode body(JsonNode row) {
        JsonNode content=row.path("content");
        if(content.isTextual()) try { content=json.readTree(content.asText()); }
        catch(IOException ignored) { return json.createObjectNode(); }
        return content==null ? json.createObjectNode() : content.path("content");
    }
    private String summary(JsonNode body) {
        for(String k:List.of("abstract","summary","guideEvidence","details")) {
            String value=Jsoup.parse(body.path(k).asText("")).text();
            if(!value.isBlank()) return limit(value,12000);
        }
        return "";
    }
    private String originalSource(JsonNode body) {
        var html=Jsoup.parse(body.path("laiyuan").asText(""));
        var link=html.selectFirst("a[href]");
        String url=link==null?html.text():link.attr("href");
        return url.matches("(?i)https?://[^\\s]+") && url.length()<=1000 ? url : "";
    }
    private List<JsonNode> files(String id) {
        List<JsonNode> list=new ArrayList<>();
        for(var f:cdss.attachments(id)) if(f.path("validFlag").asInt(1)==1 && !f.path("id").asText("").isBlank()) list.add(f);
        return list;
    }
    Detail detail(String id) {
        var row=row(id); var content=body(row);
        var files=files(id).stream().map(f->new Attachment(f.path("id").asText(),f.path("affixName").asText("附件"))).toList();
        return new Detail(item(row),summary(content),originalSource(content),files);
    }
    String[] target(String query) {
        var parts=trim(query).split("/",-1);
        if(parts.length!=2) throw bad("请选择一条业务文献及其原件或摘要");
        key(parts[0]); if(!"metadata".equals(parts[1])) key(parts[1]);
        return parts;
    }
    String urlFor(String query) { return cdss.previewUrl(target(query)[0]); }
    Acquisition.Result acquire(String query) throws IOException {
        String[] parts=target(query);
        var row=row(parts[0]); var item=item(row); var body=body(row);
        String source=DocumentReader.withDefault(originalSource(body),item.businessUrl());
        String doi=BusinessKnowledge.firstDoi(row.path("remark").asText(),source,body.path("references").asText());
        String ref="CDSS:"+item.type()+":"+item.id()+":"+item.knowledgeClass()+" "+doi;
        String summary=summary(body);
        String authors=Jsoup.parse(body.path("guideAuthor").asText("")).text();
        String publisher=Jsoup.parse(body.path("organ").asText("")).text();
        String date=Jsoup.parse(body.path("publishTime").asText("")).text();
        String origin="4.23 知识管理";
        if(parts[1].equals("metadata")) return new Acquisition.Result(new Records.Acquired(item.title(),"METADATA",
                null,null,source,item.businessUrl(),origin,authors,publisher,date,ref,summary,null,new byte[0]),"已保留业务出处和摘要");
        var file=files(item.id()).stream().filter(f->parts[1].equals(f.path("id").asText())).findFirst()
                .orElseThrow(()->bad("此附件已不在这条文献中，请刷新后重新选择"));
        String path=DocumentReader.withDefault(file.path("knowledgeAffix").asText(""),file.path("url").asText(""));
        byte[] bytes=cdss.readAttachment(path);
        String filename=file.path("affixName").asText("");
        if(filename.toLowerCase(Locale.ROOT).endsWith(".pdf") && (bytes.length<5 || bytes[0]!='%' || bytes[1]!='P' || bytes[2]!='D' || bytes[3]!='F'))
            throw bad("4.23 返回内容与 PDF 附件不符");
        var doc=reader.read(bytes,filename,"",source,cdss.attachmentUrl(path),origin,item.title());
        return new Acquisition.Result(new Records.Acquired(doc.title(),doc.formatCode(),doc.fileName(),doc.mimeType(),doc.sourceUrl(),
                doc.fetchedUrl(),origin,DocumentReader.withDefault(authors,doc.authors()),DocumentReader.withDefault(publisher,doc.publisher()),
                DocumentReader.withDefault(date,doc.published()),ref,DocumentReader.withDefault(summary,doc.sourceAbstract()),doc.rawText(),bytes),
                "从 4.23 文献取得原件，业务类别 "+item.knowledgeClass());
    }
}
