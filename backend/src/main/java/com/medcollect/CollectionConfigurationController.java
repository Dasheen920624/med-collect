package com.medcollect;

import java.security.Principal;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class CollectionConfigurationController {
    private final Classifications classifications;
    private final TemplateProfiles templates;

    CollectionConfigurationController(Classifications classifications, TemplateProfiles templates) {
        this.classifications = classifications;
        this.templates = templates;
    }

    @GetMapping("/classifications")
    List<Classifications.Node> classifications() { return classifications.nodes(); }

    @PostMapping("/classifications")
    Classifications.Node add(@Valid @RequestBody Classifications.Input input, Principal actor) {
        return classifications.save(null, input, actor.getName());
    }

    @PutMapping("/classifications/{id}")
    Classifications.Node update(@PathVariable String id, @Valid @RequestBody Classifications.Input input, Principal actor) {
        return classifications.save(id, input, actor.getName());
    }

    @PutMapping("/classifications/{id}/position")
    void move(@PathVariable String id, @Valid @RequestBody Classifications.Move input, Principal actor) {
        classifications.move(id, input, actor.getName());
    }

    @GetMapping("/template-profiles/{code}/events")
    List<Records.Event> templateEvents(@PathVariable String code, @RequestParam(required = false) String knowledgeClass) {
        return templates.events(code, knowledgeClass);
    }

    @PutMapping("/materials/classifications")
    void assign(@Valid @RequestBody Classifications.Assignment input, Principal actor) {
        classifications.assign(input, actor.getName());
    }

    @GetMapping("/template-profiles/{code}")
    KnowledgeTemplates.Schema template(@PathVariable String code, @RequestParam(required = false) String knowledgeClass) {
        return templates.schema(code, knowledgeClass);
    }

    @PutMapping("/template-profiles/{code}")
    KnowledgeTemplates.Schema saveTemplate(@PathVariable String code, @RequestParam(required = false) String knowledgeClass,
                                          @Valid @RequestBody TemplateProfiles.Input input, Principal actor) {
        return templates.save(code, knowledgeClass, input, actor.getName());
    }
}
