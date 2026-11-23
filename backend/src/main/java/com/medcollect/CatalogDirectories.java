package com.medcollect;

import static com.medcollect.Classifications.Group;
import static com.medcollect.Support.*;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Reconcile only directories referenced by collection forms; local IDs are used by existing material links. */
final class CatalogDirectories {
    private final Store store;
    private final List<Classifications.Node> existing;
    private final Set<String> seen=new HashSet<>();
    private final Map<String,Classifications.Node> remote=new HashMap<>();
    CatalogDirectories(Store store,List<Classifications.Node> existing) {
        this.store=store; this.existing=existing;
        existing.forEach(n->{ if(n.remoteKey()!=null) remote.put(n.remoteKey(),n); });
    }

    void apply(BusinessCatalog.Snapshot snapshot) {
        var parents=new HashMap<String,String>();
        int index=0;
        for(var entry:snapshot.tree()) if("17".equals(entry.parent()))
            parents.put(entry.code(),upsert(Group.SUBJECT,entry.code(),entry.name(),null,++index,null));
        for(var entry:snapshot.tree()) if(parents.containsKey(entry.parent()))
            upsert(Group.SUBJECT,entry.code(),entry.name(),parents.get(entry.parent()),++index,null);
        boolean guides=false;
        for(var kind:snapshot.kinds()) {
            upsert(Group.DOMAIN,kind.code(),kind.name(),null,++index,null);
            if("17".equals(kind.code())) guides=true;
        }
        if(guides) {
            upsert(Group.DOMAIN,"30","医学书籍",null,++index,null);
            upsert(Group.DOMAIN,"31","医学期刊",null,++index,null);
            upsert(Group.MATERIAL,"17","临床指南",null,10,"material-guideline");
            upsert(Group.MATERIAL,"30","医学书籍",null,40,"material-book");
            upsert(Group.MATERIAL,"31","医学期刊",null,50,"material-journal");
        }
        if(snapshot.kinds().stream().anyMatch(k->k.code().equals("10")))
            upsert(Group.MATERIAL,"10","临床路径",null,20,"material-pathway");
        for(var node:remote.values()) if(node.enabled() && !seen.contains(node.remoteKey()))
            store.db().sql("UPDATE mc_classification SET enabled=0,revision=revision+1 WHERE id=:id")
                    .param("id",node.id()).update();
        linkReviewedMaterials();
    }

    private String upsert(Group group,String code,String name,String parent,int sort,String seedId) {
        String key=group.name()+":"+code;
        seen.add(key);
        var row=remote.get(key);
        if(row==null) row=existing.stream().filter(n->n.remoteKey()==null && group==n.groupCode()
                && Objects.equals(n.parentId(),parent) && (code.equals(n.businessCode()) || n.id().equals(seedId)
                || (n.id().equals("subject-cardiology") && code.equals("1701")))).findFirst().orElse(null);
        if(row!=null && key.equals(row.remoteKey()) && row.enabled() && row.displayOrder()==sort
                && Objects.equals(row.parentId(),parent) && row.name().equals(name) && code.equals(row.businessCode())) return row.id();
        String nodeId=row==null?id():row.id();
        var args=new MapSqlParameterSource().addValue("id",nodeId).addValue("key",key)
                .addValue("groupName",group.name()).addValue("code",code).addValue("name",name)
                .addValue("parent",parent,Types.VARCHAR).addValue("parentKey",parent==null?"ROOT":parent)
                .addValue("nameKey","4.23/"+key).addValue("sort",sort);
        String sql=row==null ? """
            INSERT INTO mc_classification (id, group_code,parent_id,parent_key,name,name_key,business_code,
                display_order,enabled,revision,remote_key)
            VALUES (:id,:groupName,:parent,:parentKey,:name,:nameKey,:code,:sort,1,1,:key)
            """ : """
            UPDATE mc_classification SET parent_id=:parent,parent_key=:parentKey,name=:name,name_key=:nameKey,
                business_code=:code,display_order=:sort,enabled=1,revision=revision+1,remote_key=:key WHERE id=:id
            """;
        store.db().sql(sql).paramSource(args).update();
        return nodeId;
    }

    private void linkReviewedMaterials() {
        // An explicit local classification for a dimension takes precedence over inferred links.
        store.db().sql("""
            INSERT INTO mc_material_class (material_id,class_id)
            SELECT DISTINCT k.material_id, dest.id FROM mc_knowledge k
            JOIN mc_classification c ON c.business_code=k.knowledge_class AND c.group_code='SUBJECT'
            JOIN mc_classification dest ON (dest.id=c.id OR dest.id=c.parent_id)
            WHERE k.status IN ('APPROVED','SYNCED') AND c.enabled=1 AND c.remote_key IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM mc_material_class a JOIN mc_classification n ON n.id=a.class_id
                WHERE a.material_id=k.material_id AND n.group_code='SUBJECT')
            """).update();
        store.db().sql("""
            INSERT INTO mc_material_class (material_id,class_id)
            SELECT DISTINCT k.material_id,c.id FROM mc_knowledge k
            JOIN mc_classification c ON c.business_code=k.type_code AND c.group_code IN ('DOMAIN','MATERIAL')
            WHERE k.status IN ('APPROVED','SYNCED') AND c.enabled=1 AND c.remote_key IS NOT NULL
              AND (c.group_code='DOMAIN' OR NOT EXISTS (SELECT 1 FROM mc_knowledge other
                WHERE other.material_id=k.material_id AND other.status IN ('APPROVED','SYNCED') AND other.type_code<>k.type_code))
              AND NOT EXISTS (SELECT 1 FROM mc_material_class a JOIN mc_classification n ON n.id=a.class_id
                WHERE a.material_id=k.material_id AND n.group_code=c.group_code)
            """).update();
    }
}
