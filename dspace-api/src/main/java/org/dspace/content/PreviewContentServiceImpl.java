/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.dspace.app.util.Util;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.MissingLicenseAgreementException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.dao.PreviewContentDAO;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.PreviewContentService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.util.FileInfo;
import org.dspace.util.FileTreeViewGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Service implementation for the PreviewContent object.
 *
 * @author Michaela Paurikova (dspace at dataquest.sk)
 */
public class PreviewContentServiceImpl implements PreviewContentService {

    /**
     * logger
     */
    private static final Logger log = LoggerFactory.getLogger(PreviewContentServiceImpl.class);

    private final String ARCHIVE_TYPE_ZIP = "zip";
    private final String ARCHIVE_TYPE_TAR = "tar";
    // This constant is used to limit the length of the preview content stored in the database to prevent
    // the database from being overloaded with large amounts of data.
    private static final int MAX_PREVIEW_COUNT_LENGTH = 2000;
    // Initial capacity for the list of extracted file paths, set to 200 based on typical archive file counts.
    private static final int ESTIMATED_FILE_COUNT = 200;

    // Configured ZIP file preview limit (default: 1000) - if the ZIP file contains more files, it will be truncated
    @Value("${file.preview.zip.limit.length:1000}")
    private int maxPreviewCount;


    @Autowired
    PreviewContentDAO previewContentDAO;
    @Autowired(required = true)
    AuthorizeService authorizeService;
    @Autowired
    ConfigurationService configurationService;
    @Autowired
    BitstreamService bitstreamService;

    @Override
    public PreviewContent create(Context context, Bitstream bitstream, String name, String content,
                                 boolean isDirectory, String size, Map<String, PreviewContent> subPreviewContents)
            throws SQLException {
        // no authorization required!
        // Create a table row
        PreviewContent previewContent = previewContentDAO.create(context, new PreviewContent(bitstream, name, content,
                isDirectory, size, subPreviewContents));
        log.info("Created new preview content of ID = {}", previewContent.getID());
        return previewContent;
    }

    @Override
    public PreviewContent create(Context context, PreviewContent previewContent) throws SQLException {
        //no authorization required!
        PreviewContent newPreviewContent = previewContentDAO.create(context, new PreviewContent(previewContent));
        log.info("Created new preview content of ID = {}", newPreviewContent.getID());
        return newPreviewContent;
    }

    @Override
    public void delete(Context context, PreviewContent previewContent) throws SQLException, AuthorizeException {
        if (!authorizeService.isAdmin(context)) {
            throw new AuthorizeException(
                    "You must be an admin to delete an CLARIN Content Preview");
        }
        previewContentDAO.delete(context, previewContent);
    }

    @Override
    public PreviewContent find(Context context, int valueId) throws SQLException {
        return previewContentDAO.findByID(context, PreviewContent.class, valueId);
    }

    @Override
    public List<PreviewContent> findByBitstream(Context context, UUID bitstreamId) throws SQLException {
        return previewContentDAO.findByBitstream(context, bitstreamId);
    }

    @Override
    public boolean hasPreview(Context context, Bitstream bitstream) throws SQLException {
        return previewContentDAO.hasPreview(context, bitstream);
    }

    @Override
    public List<PreviewContent> getPreview(Context context, Bitstream bitstream) throws SQLException {
        return previewContentDAO.getPreview(context, bitstream);
    }

    @Override
    public List<PreviewContent> findAll(Context context) throws SQLException {
        return previewContentDAO.findAll(context, PreviewContent.class);
    }

    @Override
    public boolean canPreview(Context context, Bitstream bitstream, boolean authorization)
            throws SQLException, AuthorizeException {
        try {
            // Check it is allowed by configuration
            boolean isAllowedByCfg = configurationService.getBooleanProperty("file.preview.enabled", true);
            if (!isAllowedByCfg) {
                return false;
            }

            // Check it is allowed by license
            if (authorization) {
                authorizeService.authorizeAction(context, bitstream, Constants.READ);
            }
            return true;
        } catch (MissingLicenseAgreementException e) {
            return false;
        }
    }

    @Override
    public List<FileInfo> getFilePreviewContent(Context context, Bitstream bitstream) throws Exception {
        List<FileInfo> fileInfos = new ArrayList<>();
        File file = null;

        try {
            file = bitstreamService.retrieveFile(context, bitstream, false); // Retrieve the file

            if (Objects.nonNull(file)) {
                fileInfos = processFileToFilePreview(context, bitstream, file);
            }
        } catch (MissingLicenseAgreementException e) {
            log.error("Missing license agreement: ", e);
            throw e;
        } catch (IOException e) {
            log.error("IOException during file processing: ", e);
            throw e;
        }
        return fileInfos;
    }

    @Override
    public PreviewContent createPreviewContent(Context context, Bitstream bitstream, FileInfo fi) throws SQLException {
        Hashtable<String, PreviewContent> sub = createSubMap(fi.sub, value -> {
            try {
                return createPreviewContent(context, bitstream, value);
            } catch (SQLException e) {
                String msg = "Database error occurred while creating new preview content " +
                        "for bitstream with ID = " + bitstream.getID() + " Error msg: " + e.getMessage();
                log.error(msg, e);
                throw new RuntimeException(msg, e);
            }
        });
        return create(context, bitstream, fi.name, fi.content, fi.isDirectory, fi.size, sub);
    }

    @Override
    public FileInfo createFileInfo(PreviewContent pc) {
        Hashtable<String, FileInfo> sub = createSubMap(pc.sub, this::createFileInfo);
        return new FileInfo(pc.name, pc.content, pc.size, pc.isDirectory, sub);
    }

    @Override
    public List<FileInfo> processFileToFilePreview(Context context, Bitstream bitstream,
                                                          File file)
            throws Exception {
        List<FileInfo> fileInfos = new ArrayList<>();
        String bitstreamMimeType = bitstream.getFormat(context).getMIMEType();
        if (bitstreamMimeType.equals("text/plain")) {
            if (!validateBitstreamNameWithType(bitstream, "zip,tar,gz,tar.gz,tar.bz2")) {
                throw new IOException("The file has an incorrect type according to the MIME type stored in the " +
                        "database. This could cause the ZIP file to be previewed as a text file, potentially leading" +
                        " to a database error.");
            }
            String data = getFileContent(file, true);
            fileInfos.add(new FileInfo(data, false));
        } else if (bitstreamMimeType.equals("text/html")) {
            String data = getFileContent(file, false);
            fileInfos.add(new FileInfo(data, false));
        } else {
            String data = "";
            Map<String, String> archiveTypes = Map.of(
                    "application/zip", ARCHIVE_TYPE_ZIP,
                    "application/x-tar", ARCHIVE_TYPE_TAR
            );
            if (archiveTypes.containsKey(bitstreamMimeType)) {
                data = extractFile(file, archiveTypes.get(bitstreamMimeType));
                fileInfos = FileTreeViewGenerator.parse(data);
            }
        }
        return fileInfos;
    }

    public String composePreviewURL(Context context, Item item, Bitstream bitstream, String contextPath) {
        String identifier = null;
        if (Objects.nonNull(item) && Objects.nonNull(item.getHandle())) {
            identifier = "handle/" + item.getHandle();
        } else if (Objects.nonNull(item)) {
            identifier = "item/" + item.getID();
        } else {
            identifier = "id/" + bitstream.getID();
        }
        String url = contextPath + "/api/core/bitstreams/" + identifier;
        try {
            if (bitstream.getName() != null) {
                url += "/" + Util.encodeBitstreamName(bitstream.getName(), "UTF-8");
            }
        } catch (UnsupportedEncodingException uee) {
            log.error("UnsupportedEncodingException", uee);
        }
        url += "?sequence=" + bitstream.getSequenceID();

        String isAllowed = "n";
        try {
            if (authorizeService.authorizeActionBoolean(context, bitstream, Constants.READ)) {
                isAllowed = "y";
            }
        } catch (SQLException e) {
            log.error("Cannot authorize bitstream action because: " + e.getMessage());
        }

        url += "&isAllowed=" + isAllowed;
        return url;
    }

    /**
     * Validate the bitstream name with the specified type. Check if the ZIP file is not previewed as a text file.
     * @param bitstream
     * @param forbiddenTypes "in the form of 'type1,type2,type3'"
     * @return
     */
    private boolean validateBitstreamNameWithType(Bitstream bitstream, String forbiddenTypes) {
        ArrayList<String> forbiddenTypesList = new ArrayList(Arrays.asList(forbiddenTypes.split(",")));
        for (String forbiddenType : forbiddenTypesList) {
            if (bitstream.getName().endsWith(forbiddenType)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Define the hierarchy organization for preview content and file info.
     * The hierarchy is established by the sub map.
     * If a content item contains a sub map, it is considered a directory; if not, it is a file.
     * @param sourceMap  sub map that is used as a pattern
     * @param creator    creator function
     * @return           created sub map
     */
    private <T, U> Hashtable<String, T> createSubMap(Map<String, U> sourceMap, Function<U, T> creator) {
        if (sourceMap == null) {
            return null;
        }

        Hashtable<String, T> sub = new Hashtable<>();
        for (Map.Entry<String, U> entry : sourceMap.entrySet()) {
            sub.put(entry.getKey(), creator.apply(entry.getValue()));
        }
        return sub;
    }

    /**
     * Adds a file path and its size to the list of file paths.
     * If the path represents a directory, appends a "/" to the path.
     * @param filePaths the list of file paths to add to
     * @param path the file or directory path
     * @param size the size of the file or directory
     */
    private void addFilePath(List<String> filePaths, String path, long size) {
        try {
            boolean isDir = Files.isDirectory(Paths.get(path));
            StringBuilder sb = new StringBuilder(path.length() + 16);
            sb.append(path);
            sb.append(isDir ? "/|" : "|");
            sb.append(size);
            filePaths.add(sb.toString());
        } catch (NullPointerException | InvalidPathException | SecurityException e) {
            log.error(String.format("Failed to add file path. Path: '%s', Size: %d", path, size), e);
        }
    }

    /**
     * Processes a TAR file, extracting its entries and adding their paths to the provided list.
     * @param filePaths the list to populate with the extracted file paths
     * @param file the TAR file data
     * @throws IOException if an I/O error occurs while reading the TAR file
     */
    private void processTarFile(List<String> filePaths, File file) throws IOException {
        try (InputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis);
             // Use the constructor that accepts LongFileMode
             TarArchiveInputStream tarInput = new TarArchiveInputStream(bis)) {


            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                if (filePaths.size() >= maxPreviewCount) {
                    filePaths.add("... (too many files)");
                    break;
                }
                if (!entry.isDirectory()) {
                    String name = entry.getName();
                    long size = entry.getSize();
                    addFilePath(filePaths, name, size);
                }
                // Fully skip entry content to handle large files correctly
                skipFully(tarInput, entry.getSize());
            }
        }
    }

    /**
     * Fully skips the specified number of bytes from the input stream,
     * ensuring that all bytes are skipped even if InputStream.skip() skips less.
     *
     * @param in the input stream to skip bytes from
     * @param bytesToSkip the number of bytes to skip
     * @throws IOException if an I/O error occurs or the end of stream is reached before skipping all bytes
     */
    private void skipFully(InputStream in, long bytesToSkip) throws IOException {
        long remaining = bytesToSkip;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // If skip returns 0 or less, try to read a byte to move forward
                if (in.read() == -1) {
                    throw new IOException("Unexpected end of stream while skipping");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    /**
     * Parses a ZIP file and extracts the names and sizes of its entries.
     *
     * @param filePaths the list to populate with entry names
     * @param file      the ZIP file to read
     * @throws IOException if the file is invalid or cannot be read
     */
    private void processZipFile(List<String> filePaths, File file) throws IOException {
        try (ZipFile zipFile = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                if (filePaths.size() >= maxPreviewCount) {
                    filePaths.add("... (too many files)");
                    break;
                }
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    addFilePath(filePaths, entry.getName(), entry.getSize());
                }
            }
        }
    }

    /**
     * Builds an XML response string based on the provided list of file paths.
     * @param filePaths the list of file paths to include in the XML response
     * @return an XML string representation of the file paths
     */
    private String buildXmlResponse(List<String> filePaths) {
        StringWriter stringWriter = new StringWriter();
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        XMLStreamWriter writer = null;
        try {
            writer = factory.createXMLStreamWriter(stringWriter);

            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("root");

            int count = 0;
            for (String filePath : filePaths) {
                if (count >= maxPreviewCount) {
                    writer.writeStartElement("element");
                    writer.writeCharacters("...too many files...|0");
                    writer.writeEndElement();
                    break;
                }
                writer.writeStartElement("element");
                writer.writeCharacters(filePath);
                writer.writeEndElement();
                count++;
            }

            writer.writeEndElement(); // </root>
            writer.writeEndDocument();
            writer.flush();
            writer.close();

        } catch (Exception e) {
            log.error("Failed to build XML response", e);
            return "<root><error>Failed to generate preview</error></root>";
        }

        return stringWriter.toString();
    }

    /**
     * Processes  file data based on the specified file type (tar or zip),
     * and returns an XML representation of the file paths.
     * @param file the file data
     * @param fileType    the type of file to extract ("tar" or "zip")
     * @return an XML string representing the extracted file paths
     */
    private String extractFile(File file, String fileType) throws Exception {
        List<String> filePaths = new ArrayList<>(ESTIMATED_FILE_COUNT);
        // Process the file based on its type
        if (ARCHIVE_TYPE_TAR.equals(fileType)) {
            processTarFile(filePaths, file);
        } else {
            processZipFile(filePaths, file);
        }
        return buildXmlResponse(filePaths);
    }

    /**
     * Read file content and return as String
     * @param file the file to read
     * @param cutResult whether to limit the content length
     * @return content of the file as a String
     * @throws IOException if an error occurs reading the file
     */
    private String getFileContent(File file, boolean cutResult) throws IOException {
        StringBuilder content = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (cutResult && content.length() > MAX_PREVIEW_COUNT_LENGTH) {
                    content.append(" . . .");
                    break;
                }
                content.append(line).append("\n");
            }
        } catch (IOException e) {
            log.error("IOException during creating the preview content because: ", e);
            throw e; // Optional: rethrow if you want the exception to propagate
        }

        return cutResult ? ensureMaxLength(content.toString()) : content.toString();
    }

    /**
     * Trims the input string to ensure it does not exceed the maximum length for the database column.
     * @param input The original string to be trimmed.
     * @return A string that is truncated to the maximum length if necessary.
     */
    private static String ensureMaxLength(String input) {
        if (input == null) {
            return null;
        }

        // Check if the input string exceeds the maximum preview length
        if (input.length() > MAX_PREVIEW_COUNT_LENGTH) {
            // Truncate the string and append " . . ."
            int previewLength = MAX_PREVIEW_COUNT_LENGTH - 6; // Subtract length of " . . ."
            return input.substring(0, previewLength) + " . . .";
        } else {
            // Return the input string as is if it's within the preview length
            return input;
        }
    }
}