package com.sparipper.mobile;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class CloneExportUtils {
    private CloneExportUtils() {
    }

    static File createZip(Context context, File sourceDir) throws IOException {
        if (sourceDir == null || !sourceDir.isDirectory()) {
            throw new IOException("Clone folder is missing.");
        }

        File exportCache = new File(context.getCacheDir(), "exports");
        if (!exportCache.exists() && !exportCache.mkdirs()) {
            throw new IOException("Could not create ZIP cache.");
        }

        File zipFile = new File(exportCache, sourceDir.getName() + ".zip");
        try (
            ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(zipFile))
            )
        ) {
            zipDirectory(sourceDir, sourceDir, zip);
        }
        return zipFile;
    }

    private static void zipDirectory(File root, File current, ZipOutputStream zip) throws IOException {
        File[] children = current.listFiles();
        if (children == null) {
            throw new IOException("Could not read " + current.getName());
        }

        if (children.length == 0 && !root.equals(current)) {
            String entryName = relativePath(root, current) + "/";
            zip.putNextEntry(new ZipEntry(entryName.replace(File.separatorChar, '/')));
            zip.closeEntry();
            return;
        }

        byte[] buffer = new byte[16 * 1024];
        for (File child : children) {
            if (child.isDirectory()) {
                zipDirectory(root, child, zip);
                continue;
            }

            String entryName = relativePath(root, child).replace(File.separatorChar, '/');
            zip.putNextEntry(new ZipEntry(entryName));
            try (InputStream input = new BufferedInputStream(new FileInputStream(child))) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    zip.write(buffer, 0, read);
                }
            }
            zip.closeEntry();
        }
    }

    static void copyFileToUri(ContentResolver resolver, File source, Uri destination)
        throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("Source file is missing.");
        }

        try (
            InputStream input = new BufferedInputStream(new FileInputStream(source));
            OutputStream rawOutput = resolver.openOutputStream(destination, "w")
        ) {
            if (rawOutput == null) {
                throw new IOException("Android could not open the selected destination.");
            }

            try (OutputStream output = new BufferedOutputStream(rawOutput)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            }
        }
    }

    static Uri copyDirectoryToTree(ContentResolver resolver, Uri treeUri, File sourceDir)
        throws IOException {
        if (sourceDir == null || !sourceDir.isDirectory()) {
            throw new IOException("Clone folder is missing.");
        }

        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri treeDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
        Uri destinationRoot = DocumentsContract.createDocument(
            resolver,
            treeDocument,
            DocumentsContract.Document.MIME_TYPE_DIR,
            sourceDir.getName()
        );

        if (destinationRoot == null) {
            throw new IOException("Could not create the clone folder in the selected location.");
        }

        copyDirectoryContents(resolver, destinationRoot, sourceDir);
        return destinationRoot;
    }

    private static void copyDirectoryContents(
        ContentResolver resolver,
        Uri destinationDir,
        File sourceDir
    ) throws IOException {
        File[] children = sourceDir.listFiles();
        if (children == null) {
            throw new IOException("Could not read " + sourceDir.getName());
        }

        for (File child : children) {
            if (child.isDirectory()) {
                Uri childDir = DocumentsContract.createDocument(
                    resolver,
                    destinationDir,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    child.getName()
                );
                if (childDir == null) {
                    throw new IOException("Could not create folder " + child.getName());
                }
                copyDirectoryContents(resolver, childDir, child);
                continue;
            }

            Uri childFile = DocumentsContract.createDocument(
                resolver,
                destinationDir,
                mimeFor(child),
                child.getName()
            );
            if (childFile == null) {
                throw new IOException("Could not create file " + child.getName());
            }
            copyFileToUri(resolver, child, childFile);
        }
    }

    static String mimeFor(File file) {
        String name = file == null ? "" : file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }

            if ("js".equals(extension) || "mjs".equals(extension) || "cjs".equals(extension)) {
                return "text/javascript";
            }
            if ("json".equals(extension) || "webmanifest".equals(extension)) {
                return "application/json";
            }
            if ("svg".equals(extension)) {
                return "image/svg+xml";
            }
            if ("woff2".equals(extension)) {
                return "font/woff2";
            }
            if ("m3u8".equals(extension)) {
                return "application/vnd.apple.mpegurl";
            }
            if ("vtt".equals(extension)) {
                return "text/vtt";
            }
        }
        return "application/octet-stream";
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";

        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        int index = -1;
        do {
            value /= 1024.0;
            index++;
        } while (value >= 1024.0 && index < units.length - 1);

        return String.format(Locale.ROOT, "%.1f %s", value, units[index]);
    }

    private static String relativePath(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Refusing to export a file outside the clone folder.");
        }
        return filePath.substring(rootPath.length() + 1);
    }
}
