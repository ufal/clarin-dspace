/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    public List<PreviewContent> hasPreview(Context context, Bitstream bitstream) throws SQLException {
        return previewContentDAO.hasPreview(context, bitstream);
    }

    @Override
    public List<PreviewContent> findAll(Context context) throws SQLException {
        return previewContentDAO.findAll(context, PreviewContent.class);
    }

    @Override
    public boolean canPreview(Context context, Bitstream bitstream) throws SQLException, AuthorizeException {
        try {
            // Check it is allowed by configuration
            boolean isAllowedByCfg = configurationService.getBooleanProperty("file.preview.enabled", true);
            if (!isAllowedByCfg) {
                return false;
            }

            // Check it is allowed by license
            authorizeService.authorizeAction(context, bitstream, Constants.READ);
            return true;
        } catch (MissingLicenseAgreementException e) {
            return false;
        }
    }

    @Override
    public List<FileInfo> getFilePreviewContent(Context context, Bitstream bitstream)
            throws SQLException, AuthorizeException, IOException {
        InputStream inputStream = null;
        List<FileInfo> fileInfos = null;
        try {
            inputStream = bitstreamService.retrieve(context, bitstream);
        } catch (MissingLicenseAgreementException e) { /* Do nothing */ }

        if (Objects.nonNull(inputStream)) {
            try {
                fileInfos = processInputStreamToFilePreview(context, bitstream, inputStream);
            } catch (IllegalStateException e) {
                log.error("Cannot process Input Stream to file preview because: " + e.getMessage());
            }
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
    public List<FileInfo> processInputStreamToFilePreview(Context context, Bitstream bitstream,
                                                          InputStream inputStream)
            throws SQLException, IOException {
        List<FileInfo> fileInfos = new ArrayList<>();
        String bitstreamMimeType = bitstream.getFormat(context).getMIMEType();
        if (bitstreamMimeType.equals("text/plain")) {
            if (!validateBitstreamNameWithType(bitstream, "zip,tar,gz,tar.gz,tar.bz2")) {
                throw new IOException("The file has an incorrect type according to the MIME type stored in the " +
                        "database. This could cause the ZIP file to be previewed as a text file, potentially leading" +
                        " to a database error.");
            }
            String data = getFileContent(inputStream, true);
            fileInfos.add(new FileInfo(data, false));
        } else if (bitstreamMimeType.equals("text/html")) {
            String data = getFileContent(inputStream, false);
            fileInfos.add(new FileInfo(data, false));
        } else {
            String data = "";
            Map<String, String> archiveTypes = Map.of(
                    "application/zip", ARCHIVE_TYPE_ZIP,
                    "application/x-tar", ARCHIVE_TYPE_TAR
            );

            String mimeType = bitstream.getFormat(context).getMIMEType();
            if (archiveTypes.containsKey(mimeType)) {
                try {
                    data = extractFile(inputStream, archiveTypes.get(mimeType));
                    fileInfos = FileTreeViewGenerator.parse(data);
                } catch (Exception e) {
                    log.error("Cannot extract file content because: {}", e.getMessage());
                }
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
     * Creates a temporary file with the appropriate extension based on the specified file type.
     * @param fileType the type of file for which to create a temporary file
     * @return a Path object representing the temporary file
     * @throws IOException if an I/O error occurs while creating the file
     */
    private Path createTempFile(String fileType) throws IOException {
        String extension = ARCHIVE_TYPE_TAR.equals(fileType) ?
                String.format(".%s", ARCHIVE_TYPE_TAR) : String.format(".%s", ARCHIVE_TYPE_ZIP);
        return Files.createTempFile("temp", extension);
    }

    /**
     * Adds a file path and its size to the list of file paths.
     * If the path represents a directory, appends a "/" to the path.
     * @param filePaths the list of file paths to add to
     * @param path the file or directory path
     * @param size the size of the file or directory
     */
    private void addFilePath(List<String> filePaths, String path, long size) {
        String fileInfo = (Files.isDirectory(Paths.get(path))) ? path + "/|" + size : path + "|" + size;
        filePaths.add(fileInfo);
    }

    /**
     * Processes a TAR file, extracting its entries and adding their paths to the provided list.
     * @param filePaths the list to populate with the extracted file paths
     * @param tempFile  the temporary TAR file to process
     * @throws IOException if an I/O error occurs while reading the TAR file
     */
    private void processTarFile(List<String> filePaths, Path tempFile) throws IOException {
        try (InputStream fi = Files.newInputStream(tempFile);
             TarArchiveInputStream tis = new TarArchiveInputStream(fi)) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                addFilePath(filePaths, entry.getName(), entry.getSize());
            }
        }
    }

    /**
     * Processes a ZIP file, extracting its entries and adding their paths to the provided list.
     * @param filePaths      the list to populate with the extracted file paths
     * @param zipFileSystem  the FileSystem object representing the ZIP file
     * @throws IOException if an I/O error occurs while reading the ZIP file
     */
    private void processZipFile(List<String> filePaths, FileSystem zipFileSystem) throws IOException {
        Path root = zipFileSystem.getPath("/");
        Files.walk(root).forEach(path -> {
            try {
                long fileSize = Files.size(path);
                addFilePath(filePaths, path.toString().substring(1), fileSize);
            } catch (IOException e) {
                log.error("An error occurred while getting the size of the zip file.", e);
            }
        });
    }

    /**
     * Closes the specified FileSystem resource if it is not null.
     * @param zipFileSystem the FileSystem to close
     */
    private void closeFileSystem(FileSystem zipFileSystem) {
        if (Objects.nonNull(zipFileSystem)) {
            try {
                zipFileSystem.close();
            } catch (IOException e) {
                log.error("An error occurred while closing the zip file.", e);
            }
        }
    }

    /**
     * Deletes the specified temporary file if it is not null.
     * @param tempFile the Path object representing the temporary file to delete
     */
    private void deleteTempFile(Path tempFile) {
        if (Objects.nonNull(tempFile)) {
            try {
                Files.delete(tempFile);
            } catch (IOException e) {
                log.error("An error occurred while deleting temp file.", e);
            }
        }
    }

    /**
     * Builds an XML response string based on the provided list of file paths.
     * @param filePaths the list of file paths to include in the XML response
     * @return an XML string representation of the file paths
     */
    private String buildXmlResponse(List<String> filePaths) {
        // Is a folder regex
        String folderRegex = "/|\\d+";
        Pattern pattern = Pattern.compile(folderRegex);

        StringBuilder sb = new StringBuilder();
        sb.append("<root>");
        Iterator<String> iterator = filePaths.iterator();
        int fileCounter = 0;
        while (iterator.hasNext() && fileCounter < maxPreviewCount) {
            String filePath = iterator.next();
            // Check if the file is a folder
            Matcher matcher = pattern.matcher(filePath);
            if (!matcher.matches()) {
                // It is a file
                fileCounter++;
            }
            sb.append("<element>").append(filePath).append("</element>");
        }

        if (fileCounter > maxPreviewCount) {
            sb.append("<element>...too many files...|0</element>");
        }
        sb.append("</root>");
        return sb.toString();
    }

    /**
     * Extracts files from an InputStream, processes them based on the specified file type (tar or zip),
     * and returns an XML representation of the file paths.
     * @param inputStream the InputStream containing the file data
     * @param fileType    the type of file to extract ("tar" or "zip")
     * @return an XML string representing the extracted file paths
     */
    private String extractFile(InputStream inputStream, String fileType) {
        List<String> filePaths = new ArrayList<>();
        Path tempFile = null;
        FileSystem zipFileSystem = null;

        try {
            // Create a temporary file based on the file type
            tempFile = createTempFile(fileType);

            // Copy the input stream to the temporary file
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

            // Process the file based on its type
            if (ARCHIVE_TYPE_TAR.equals(fileType)) {
                processTarFile(filePaths, tempFile);
            } else {
                zipFileSystem = FileSystems.newFileSystem(tempFile, (ClassLoader) null);
                processZipFile(filePaths, zipFileSystem);
            }
        } catch (IOException e) {
            log.error(String.format("An error occurred while extracting file of type %s.", fileType), e);
        } finally {
            closeFileSystem(zipFileSystem);
            deleteTempFile(tempFile);
        }

        return buildXmlResponse(filePaths);
    }

    /**
     * Read input stream and return content as String
     * @param inputStream to read
     * @return content of the inputStream as a String
     * @throws IOException
     */
    private String getFileContent(InputStream inputStream, boolean cutResult) throws IOException {
        StringBuilder content = new StringBuilder();
        // Generate the preview content in the UTF-8 encoding
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (UnsupportedEncodingException e) {
            log.error("UnsupportedEncodingException during creating the preview content because: ", e);
        } catch (IOException e) {
            log.error("IOException during creating the preview content because: ", e);
        }

        reader.close();
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
