package com.medcollect;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/materials/{id}/pdf-preview")
class PdfPreview {
    private final Store store;
    private final Semaphore renderSlots = new Semaphore(2);

    PdfPreview(Store store) { this.store = store; }

    private PDDocument open(String id) throws IOException {
        var material = store.material(id);
        if (!material.pdf()) throw Support.bad("该原件不是 PDF");
        return Loader.loadPDF(store.bytes(material));
    }

    @GetMapping
    ResponseEntity<Map<String, Integer>> info(@PathVariable String id) throws IOException {
        try (var doc = open(id)) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("pages", doc.getNumberOfPages()));
        }
    }

    @GetMapping("/{page}")
    ResponseEntity<byte[]> page(@PathVariable String id, @PathVariable int page) throws IOException {
        if (!renderSlots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "页面正在生成，请稍后重试");
        try (var doc = open(id)) {
            if (page < 1 || page > doc.getNumberOfPages()) throw Support.bad("PDF 页码超出范围");
            var bounds = doc.getPage(page - 1).getCropBox();
            float longest = Math.max(bounds.getWidth(), bounds.getHeight());
            if (!Float.isFinite(longest) || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) throw Support.bad("PDF 页面尺寸无效");
            var renderer = new PDFRenderer(doc);
            renderer.setSubsamplingAllowed(true);
            BufferedImage image = renderer.renderImage(page - 1, Math.min(3f, 1680f / longest), ImageType.RGB);
            try (var output = new ByteArrayOutputStream()) {
                ImageIO.write(image, "jpeg", output);
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(output.toByteArray());
            } finally {
                image.flush();
            }
        } finally {
            renderSlots.release();
        }
    }
}
