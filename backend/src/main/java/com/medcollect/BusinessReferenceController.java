package com.medcollect;

import static com.medcollect.Records.*;
import java.security.Principal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api")
@Validated
class BusinessReferenceController {
    private final BusinessCatalog catalog;
    private final BusinessDictionaries dictionaries;
    private final BusinessLiterature literature;
    private final CollectionService collection;
    BusinessReferenceController(BusinessCatalog catalog,BusinessDictionaries dictionaries,BusinessLiterature literature,CollectionService collection) {
        this.catalog=catalog; this.dictionaries=dictionaries; this.literature=literature; this.collection=collection;
    }
    @GetMapping("/business-catalog")
    BusinessCatalog.Status status() { return catalog.status(); }
    @PostMapping("/business-catalog/refresh")
    BusinessCatalog.Status refresh(Principal actor) { return catalog.refresh(actor.getName()); }
    @GetMapping("/business-catalog/parameters/{name}")
    List<BusinessCatalog.Option> parameters(@PathVariable String name) { return dictionaries.parameters(name,false); }
    @GetMapping("/business-catalog/relations/{type}/{key}")
    Page<BusinessCatalog.Option> options(@PathVariable String type,@PathVariable String key,
                @RequestParam(defaultValue="") @Size(max=100) String query,@RequestParam(defaultValue="1") int page,
                @RequestParam(defaultValue="12") int size) { return dictionaries.search(type,key,query,page,size); }
    @GetMapping("/business-catalog/relations/{type}/{key}/selected")
    List<BusinessCatalog.Option> selected(@PathVariable String type,@PathVariable String key,@RequestParam List<String> values) {
        return dictionaries.resolve(type,key,values,false);
    }
    @GetMapping("/business-literature")
    Page<BusinessLiterature.Literature> literature(@RequestParam(defaultValue="31") String type,
            @RequestParam(defaultValue="") @Size(max=100) String query,@RequestParam(defaultValue="") @Size(max=80) String knowledgeClass,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size) {
        return literature.search(type,query,knowledgeClass,page,size);
    }
    @GetMapping("/business-literature/{id}")
    BusinessLiterature.Detail detail(@PathVariable String id) { return literature.detail(id); }
    record Selection(@Size(max=80) String attachmentId) {}
    @PostMapping("/business-literature/{id}/collect")
    Run collect(@PathVariable String id,@Valid @RequestBody Selection input,Principal actor) {
        var detail=literature.detail(id);
        String file=Support.trim(input.attachmentId());
        if(!file.isBlank() && detail.attachments().stream().noneMatch(f->f.id().equals(file))) throw Support.bad("请从本条业务文献中选择原件");
        String query=BusinessLiterature.key(id)+"/"+(file.isBlank()?"metadata":BusinessLiterature.key(file));
        var task=collection.saveTask(null,new TaskInput(Support.limit(detail.item().title(),120),Mode.BUSINESS,query,null,
                CaptureMode.SINGLE,1,0,true,null,ScheduleMode.MANUAL,null,null,false,null,null,null),actor.getName());
        return collection.start(task.id(),actor.getName());
    }
}
