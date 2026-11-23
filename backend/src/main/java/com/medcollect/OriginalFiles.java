package com.medcollect;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

@Component
class OriginalFiles {
    private static final Pattern MATERIAL_ID = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern STORAGE_PATH = Pattern.compile("[0-9a-f]{2}/" + MATERIAL_ID.pattern()
            + "/original\\.[a-z]{2,6}");
    private static final String EXTRACTED_TEXT = "extracted.txt";

    private final Path root;

    OriginalFiles(@Value("${medcollect.original-dir}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @PostConstruct
    void prepare() throws IOException {
        Files.createDirectories(root);
        if (!Files.isWritable(root)) throw new IOException("Original storage is not writable");
    }

    String write(String materialId, String format, byte[] bytes, String extracted) throws IOException {
        if (!MATERIAL_ID.matcher(materialId).matches()) throw new IOException("Invalid material ID");
        String prefix = materialId.substring(0, 2);
        String fileName = "original." + extension(format);
        String relative = prefix + "/" + materialId + "/" + fileName;
        String text = extracted == null || extracted.isEmpty() ? null : extracted;

        Path base = root.toRealPath();
        Path shard = Files.createDirectories(base.resolve(prefix));
        if (!Files.isDirectory(shard, NOFOLLOW_LINKS)) throw new IOException("Invalid original storage directory");
        Path destination = shard.resolve(materialId);
        if (Files.exists(destination, NOFOLLOW_LINKS)) {
            if (!Arrays.equals(read(relative), bytes) || !Objects.equals(text(relative), text)) {
                throw new IOException("Stored files do not match material " + materialId);
            }
            return relative;
        }

        Path stage = Files.createTempDirectory(shard, ".original-");
        try {
            writeFile(stage.resolve(fileName), bytes);
            if (text != null) writeFile(stage.resolve(EXTRACTED_TEXT), text.getBytes(StandardCharsets.UTF_8));
            syncDirectory(stage);
            Files.move(stage, destination, ATOMIC_MOVE);
            syncDirectory(shard);
            syncDirectory(base);
        } finally {
            deleteFiles(stage, fileName);
        }
        return relative;
    }

    byte[] read(String relative) throws IOException {
        try (var file = Files.newInputStream(original(relative))) {
            byte[] bytes = file.readNBytes(DocumentReader.MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > DocumentReader.MAX_BYTES) {
                throw new IOException("Stored original has an invalid size");
            }
            return bytes;
        }
    }

    String text(String relative) throws IOException {
        Path file = original(relative).resolveSibling(EXTRACTED_TEXT);
        if (Files.notExists(file, NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw new IOException("Invalid extracted text file");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    void discard(String relative) throws IOException {
        Path file = original(relative);
        deleteFiles(file.getParent(), file.getFileName().toString());
    }

    private Path original(String relative) throws IOException {
        if (relative == null || !STORAGE_PATH.matcher(relative).matches()) {
            throw new IOException("Invalid original storage path");
        }
        Path file = root.toRealPath().resolve(relative);
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || !file.toRealPath().equals(file)) {
            throw new IOException("Stored original is unavailable");
        }
        return file;
    }

    private static String extension(String format) {
        return switch (format) {
            case "PDF", "HTML", "DOCX", "TXT", "CSV", "XLS", "XLSX" -> format.toLowerCase(Locale.ROOT);
            case "METADATA" -> "json";
            default -> "bin";
        };
    }

    private static void writeFile(Path file, byte[] bytes) throws IOException {
        Files.write(file, bytes, CREATE_NEW, WRITE);
        try (var channel = FileChannel.open(file, WRITE)) {
            channel.force(true);
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            try (var channel = FileChannel.open(directory, READ)) {
                channel.force(true);
            }
        }
    }

    private static void deleteFiles(Path directory, String originalName) throws IOException {
        Files.deleteIfExists(directory.resolve(originalName));
        Files.deleteIfExists(directory.resolve(EXTRACTED_TEXT));
        Files.deleteIfExists(directory);
    }
}
