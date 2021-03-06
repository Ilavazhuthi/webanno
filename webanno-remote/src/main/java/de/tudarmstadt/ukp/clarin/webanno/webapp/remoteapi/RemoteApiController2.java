/*
 * Copyright 2017
 * Ubiquitous Knowledge Processing (UKP) Lab and FG Language Technology
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.clarin.webanno.webapp.remoteapi;

import static de.tudarmstadt.ukp.clarin.webanno.api.SecurityUtil.isProjectAdmin;
import static de.tudarmstadt.ukp.clarin.webanno.api.SecurityUtil.isProjectCreator;
import static de.tudarmstadt.ukp.clarin.webanno.api.SecurityUtil.isSuperAdmin;
import static org.apache.uima.fit.util.JCasUtil.select;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Resource;
import javax.persistence.NoResultException;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.uima.analysis_component.JCasAnnotator_ImplBase;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Feature;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.impl.CASImpl;
import org.apache.uima.cas.impl.FeatureImpl;
import org.apache.uima.cas.impl.TypeImpl;
import org.apache.uima.cas.impl.TypeSystemImpl;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.collection.CollectionReader;
import org.apache.uima.fit.util.FSUtil;
import org.apache.uima.jcas.JCas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import de.tudarmstadt.ukp.clarin.webanno.api.AnnotationSchemaService;
import de.tudarmstadt.ukp.clarin.webanno.api.DocumentService;
import de.tudarmstadt.ukp.clarin.webanno.api.ImportExportService;
import de.tudarmstadt.ukp.clarin.webanno.api.ProjectService;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.Mode;
import de.tudarmstadt.ukp.clarin.webanno.model.PermissionLevel;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.ProjectPermission;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.security.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.security.model.User;
import de.tudarmstadt.ukp.clarin.webanno.tsv.WebannoTsv3Writer;
import de.tudarmstadt.ukp.clarin.webanno.webapp.remoteapi.v2.exception.AccessForbiddenException;
import de.tudarmstadt.ukp.clarin.webanno.webapp.remoteapi.v2.exception.IncompatibleDocumentException;
import de.tudarmstadt.ukp.clarin.webanno.webapp.remoteapi.v2.exception.ObjectExistsException;
import de.tudarmstadt.ukp.clarin.webanno.webapp.remoteapi.v2.exception.ObjectNotFoundException;
import de.tudarmstadt.ukp.clarin.webanno.webapp.remoteapi.v2.exception.RemoteApiException;
import de.tudarmstadt.ukp.clarin.webanno.webapp.remoteapi.v2.exception.UnsupportedFormatException;
import de.tudarmstadt.ukp.clarin.webanno.webapp.remoteapi.v2.model.RAnnotation;
import de.tudarmstadt.ukp.clarin.webanno.webapp.remoteapi.v2.model.RDocument;
import de.tudarmstadt.ukp.clarin.webanno.webapp.remoteapi.v2.model.RProject;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Info;
import io.swagger.annotations.SwaggerDefinition;

@SwaggerDefinition(info = @Info(title = "WebAnno Remote API", version = "2"))
@RequestMapping(RemoteApiController2.API_BASE)
@Controller
public class RemoteApiController2
{
    public static final String API_BASE = "/api/v2";
    
    private static final String PROJECTS = "projects";
    private static final String DOCUMENTS = "documents";
    private static final String ANNOTATIONS = "annotations";
    
    private static final String PARAM_FILE = "file";
    private static final String PARAM_NAME = "name";
    private static final String PARAM_FORMAT = "format";
    private static final String PARAM_CREATOR = "creator";
    private static final String PARAM_PROJECT_ID = "projectId";
    private static final String PARAM_ANNOTATOR_ID = "userId";
    private static final String PARAM_DOCUMENT_ID = "documentId";
    
    private static final String VAL_ORIGINAL = "ORIGINAL";
    
    private static final String PROP_ID = "id";
    private static final String PROP_NAME = "name";
    private static final String PROP_STATE = "state";
    private static final String PROP_USER = "user";
    private static final String PROP_TIMESTAMP = "user";
    
    private static final String FORMAT_DEFAULT = "text";
    
    private final Logger LOG = LoggerFactory.getLogger(getClass());

    @Resource(name = "documentService")
    private DocumentService documentService;

    @Resource(name = "projectService")
    private ProjectService projectService;

    @Resource(name = "importExportService")
    private ImportExportService importExportService;

    @Resource(name = "annotationService")
    private AnnotationSchemaService annotationService;

    @Resource(name = "userRepository")
    private UserDao userRepository;
    
    @ExceptionHandler(value = RemoteApiException.class)
    public void handleException(RemoteApiException aException, HttpServletResponse aResponse) 
        throws IOException
    {
        LOG.error(aException.getMessage(), aException);
        aResponse.setStatus(aException.getStatus().value());
        try (PrintWriter out = aResponse.getWriter()) {
            out.print(aException.getMessage());
        }
    }

    @ExceptionHandler
    public void handleException(Exception aException, HttpServletResponse aResponse)
        throws IOException
    {
        LOG.error(aException.getMessage(), aException);
        aResponse.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        try (PrintWriter out = aResponse.getWriter()) {
            out.print("Internal server error: ");
            out.print(aException.getMessage());
        }
    }

    private User getCurrentUser()
        throws ObjectNotFoundException
    {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return getUser(username);
    }

    private User getUser(String aUserId)
        throws ObjectNotFoundException
    {
        User user = userRepository.get(aUserId);
        if (user == null) {
            throw new ObjectNotFoundException("User [" + aUserId + "] not found.");
        }
        return user;
    }

    private Project getProject(long aProjectId)
        throws ObjectNotFoundException, AccessForbiddenException
    {
        // Get current user - this will throw an exception if the current user does not exit
        User user = getCurrentUser();
        
        // Get project
        Project project;
        try {
            project = projectService.getProject(aProjectId);
        }
        catch (NoResultException e) {
            throw new ObjectNotFoundException("Project [" + aProjectId + "] not found.");
        }
        
        // Check for the access
        assertPermission(
                "User [" + user.getUsername() + "] is not allowed to access project [" + aProjectId
                        + "]",
                isProjectAdmin(project, projectService, user)
                        || isSuperAdmin(projectService, user));
        
        return project;
    }
    
    private SourceDocument getDocument(Project aProject, long aDocumentId)
        throws ObjectNotFoundException
    {
        try {
            return documentService.getSourceDocument(aProject.getId(), aDocumentId);
        }
        catch (NoResultException e) {
            throw new ObjectNotFoundException(
                    "Document [" + aDocumentId + "] in project [" + aProject.getId() + "] not found.");
        }
    }

    private AnnotationDocument getAnnotation(SourceDocument aDocument, String aUser,
            boolean aCreateIfMissing)
        throws ObjectNotFoundException
    {
        try {
            if (aCreateIfMissing) {
                return documentService.createOrGetAnnotationDocument(aDocument, getUser(aUser));
            }
            else {
                return documentService.getAnnotationDocument(aDocument, getUser(aUser));
            }
        }
        catch (NoResultException e) {
            throw new ObjectNotFoundException(
                    "Annotation for user [" + aUser + "] on document [" + aDocument.getId()
                            + "] in project [" + aDocument.getProject().getId() + "] not found.");
        }
    }

    private void assertPermission(String aMessage, boolean aHasAccess)
        throws AccessForbiddenException
    {
        if (!aHasAccess) {
            throw new AccessForbiddenException(aMessage);
        }
    }
    
    @ApiOperation(value = "List the projects accessible by the authenticated user")
    @RequestMapping(
            value = ("/" + PROJECTS), 
            method = RequestMethod.GET, 
            produces = MediaType.APPLICATION_JSON_UTF8_VALUE)    
    public ResponseEntity<List<RProject>> projectList()
        throws Exception
    {
        // Get current user - this will throw an exception if the current user does not exit
        User user = getCurrentUser();

        // Get projects with permission
        List<Project> accessibleProjects = projectService.listAccessibleProjects(user);

        // Collect all the projects
        List<RProject> projectList = new ArrayList<>();
        for (Project project : accessibleProjects) {
            projectList.add(new RProject(project));
        }
        return ResponseEntity.ok(projectList);
    }
    
    @ApiOperation(value = "Create a new project")
    @RequestMapping(
            value = ("/" + PROJECTS), 
            method = RequestMethod.POST, 
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_UTF8_VALUE)    
    public ResponseEntity<RProject> projectCreate(
            @RequestParam(PARAM_NAME) String aName, 
            @RequestParam(PARAM_CREATOR) Optional<String> aCreator,
            UriComponentsBuilder aUcb)
        throws Exception
    {
        // Get current user - this will throw an exception if the current user does not exit
        User user = getCurrentUser();

        // Check for the access
        assertPermission("User [" + user.getUsername() + "] is not allowed to create projects",
                isProjectCreator(projectService, user) || isSuperAdmin(projectService, user));
        
        // Check if the user can create projects for another user
        assertPermission(
                "User [" + user.getUsername() + "] is not allowed to create projects for user ["
                        + aCreator.orElse("<unspecified>") + "]",
                isSuperAdmin(projectService, user)
                        || (aCreator.isPresent() && aCreator.equals(user.getUsername())));
        
        // Existing project
        if (projectService.existsProject(aName)) {
            throw new ObjectExistsException("A project with name [" + aName + "] already exists");
        }

        // Create the project and initialize tags
        LOG.info("Creating project [" + aName + "]");
        Project project = new Project();
        project.setName(aName);
        projectService.createProject(project);
        annotationService.initializeTypesForProject(project);
        
        // Create permission for the project creator
        String owner = aCreator.isPresent() ? aCreator.get() : user.getUsername();
        projectService.createProjectPermission(
                new ProjectPermission(project, owner, PermissionLevel.ADMIN));
        projectService.createProjectPermission(
                new ProjectPermission(project, owner, PermissionLevel.CURATOR));
        projectService.createProjectPermission(
                new ProjectPermission(project, owner, PermissionLevel.USER));
        
        RProject response = new RProject(project);
        return ResponseEntity.created(aUcb.path(API_BASE + "/" + PROJECTS + "/{id}")
                .buildAndExpand(project.getId()).toUri()).body(response);
    }
    
    @ApiOperation(value = "Get information about a project")
    @RequestMapping(
            value = ("/" + PROJECTS + "/{" + PARAM_PROJECT_ID + "}"), 
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_UTF8_VALUE)    
    public ResponseEntity<RProject> projectRead(
            @PathVariable(PARAM_PROJECT_ID) long aProjectId)
        throws Exception
    {
        // Get project (this also ensures that it exists and that the current user can access it
        Project project = getProject(aProjectId);

        RProject response = new RProject(project);
        return ResponseEntity.ok(response);
    }
    
    @ApiOperation(value = "Delete an existing project")
    @RequestMapping(
            value = ("/" + PROJECTS + "/{" + PARAM_PROJECT_ID + "}"), 
            method = RequestMethod.DELETE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> projectDelete(
            @PathVariable(PARAM_PROJECT_ID) long aProjectId)
        throws Exception
    {
        // Get project (this also ensures that it exists and that the current user can access it
        Project project = getProject(aProjectId);
        
        projectService.removeProject(project);
        return ResponseEntity.ok("Project [" + aProjectId + "] deleted.");
    }

    @ApiOperation(value = "List documents in a project")
    @RequestMapping(
            value = "/" + PROJECTS + "/{" + PARAM_PROJECT_ID + "}/" + DOCUMENTS, 
            method = RequestMethod.GET, 
            produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
    public ResponseEntity<List<RDocument>> documentList(
            @PathVariable(PARAM_PROJECT_ID) long aProjectId)
        throws Exception
    {               
        // Get project (this also ensures that it exists and that the current user can access it
        Project project = getProject(aProjectId);
        
        List<SourceDocument> documents = documentService.listSourceDocuments(project);
        
        List<RDocument> documentList = new ArrayList<>();
        for (SourceDocument document : documents) { 
            documentList.add(new RDocument(document));
        }
        
        return ResponseEntity.ok(documentList);
    }
    
    @ApiOperation(value = "Create a new document in a project")
    @RequestMapping(
            value = "/" + PROJECTS + "/{" + PARAM_PROJECT_ID + "}/" + DOCUMENTS, 
            method = RequestMethod.POST,
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
    public ResponseEntity<RDocument> documentCreate(
            @PathVariable(PARAM_PROJECT_ID) long aProjectId,
            @RequestParam(value = PARAM_FILE) MultipartFile aFile,
            @RequestParam(value = PARAM_NAME) String aName,
            @RequestParam(value = PARAM_FORMAT) String aFormat,
            UriComponentsBuilder aUcb)
        throws Exception
    {               
        // Get project (this also ensures that it exists and that the current user can access it
        Project project = getProject(aProjectId);

        // Check if the format is supported
        Map<String, Class<CollectionReader>> readableFormats = importExportService
                .getReadableFormats();
        if (readableFormats.get(aFormat) == null) {
            throw new UnsupportedFormatException(
                    "Format [%s] not supported. Acceptable formats are %s.", aFormat,
                    readableFormats.keySet());
        }
        
        // Meta data entry to the database
        SourceDocument document = new SourceDocument();
        document.setProject(project);
        document.setName(aName);
        document.setFormat(aFormat);
        
        // Import source document to the project repository folder
        try (InputStream is = aFile.getInputStream()) {
            documentService.uploadSourceDocument(is, document);
        }
        
        RDocument rDocument = new RDocument(document);
        
        return ResponseEntity
                .created(aUcb.path(API_BASE + "/" + PROJECTS + "/{pid}/" + DOCUMENTS + "/{did}")
                        .buildAndExpand(project.getId(), document.getId()).toUri())
                .body(rDocument);
    }

    @ApiOperation(value = "Get a document from a project", response = byte[].class)
    @RequestMapping(
            value = "/" + PROJECTS + "/{" + PARAM_PROJECT_ID + "}/" + DOCUMENTS + "/{"
                    + PARAM_DOCUMENT_ID + "}",
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity documentRead(
            @PathVariable(PARAM_PROJECT_ID) long aProjectId,
            @PathVariable(PARAM_DOCUMENT_ID) long aDocumentId,
            @RequestParam(value = PARAM_FORMAT) Optional<String> aFormat)
        throws Exception
    {               
        // Get project (this also ensures that it exists and that the current user can access it
        Project project = getProject(aProjectId);
        
        SourceDocument doc = getDocument(project, aDocumentId);
        
        boolean originalFile;
        String format;
        if (aFormat.isPresent()) {
            if (VAL_ORIGINAL.equals(aFormat.get())) {
                format = doc.getFormat();
                originalFile = true;
            }
            else {
                format = aFormat.get();
                originalFile = doc.getFormat().equals(format);
            }
        }
        else {
            format = doc.getFormat();
            originalFile = true;
        }
        
        if (originalFile) {
            // Export the original file - no temporary file created here, we export directly from
            // the file system
            File docFile = documentService.getSourceDocumentFile(doc);
            FileSystemResource resource = new FileSystemResource(docFile);
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentLength(resource.contentLength());
            httpHeaders.set("Content-Disposition",
                    "attachment; filename=\"" + doc.getName() + "\"");
            return new ResponseEntity<org.springframework.core.io.Resource>(resource, httpHeaders,
                    HttpStatus.OK);
        }
        else {
            // Export a converted file - here we first export to a local temporary file and then
            // send that back to the client
            
            // Check if the format is supported
            Map<String, Class<JCasAnnotator_ImplBase>> writableFormats = importExportService
                    .getWritableFormats();
            Class<JCasAnnotator_ImplBase> writer = writableFormats.get(format);
            if (writer == null) {
                throw new UnsupportedFormatException(
                        "Format [%s] cannot be exported. Exportable formats are %s.", aFormat,
                        writableFormats.keySet());
            }
            
            // Create a temporary export file from the annotations
            JCas jcas = documentService.createOrReadInitialCas(doc);
            
            File exportedFile = null;
            try {
                // Load the converted file into memory
                exportedFile = importExportService.exportCasToFile(jcas.getCas(), doc,
                        doc.getName(), writer, true);
                byte[] resource = FileUtils.readFileToByteArray(exportedFile);
                
                // Send it back to the client
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.setContentLength(resource.length);
                httpHeaders.set("Content-Disposition",
                        "attachment; filename=\"" + exportedFile.getName() + "\"");
                
                return new ResponseEntity<>(resource, httpHeaders, HttpStatus.OK);
            }
            finally {
                if (exportedFile != null) {
                    FileUtils.forceDelete(exportedFile);
                }
            }
        }
    }
    
    @ApiOperation(value = "Delete a document from a project")
    @RequestMapping(
            value = "/" + PROJECTS + "/{" + PARAM_PROJECT_ID + "}/" + DOCUMENTS + "/{" 
                    + PARAM_DOCUMENT_ID + "}/", 
            method = RequestMethod.DELETE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> documentDelete(
            @PathVariable(PARAM_PROJECT_ID) long aProjectId,
            @PathVariable(PARAM_DOCUMENT_ID) long aDocumentId)
        throws Exception
    {               
        // Get project (this also ensures that it exists and that the current user can access it
        Project project = getProject(aProjectId);
        
        SourceDocument doc = getDocument(project, aDocumentId);
        documentService.removeSourceDocument(doc);
        
        return ResponseEntity
                .ok("Document [" + aDocumentId + "] deleted from project [" + aProjectId + "].");
    }
    
    @ApiOperation(value = "List annotations of a document in a project")
    @RequestMapping(
            value = "/" + PROJECTS + "/{" + PARAM_PROJECT_ID + "}/" + DOCUMENTS + "/{"
                    + PARAM_DOCUMENT_ID + "}/" + ANNOTATIONS,
            method = RequestMethod.GET, 
            produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
    public ResponseEntity<List<RAnnotation>> annotationsList(
            @PathVariable(PARAM_PROJECT_ID) long aProjectId,
            @PathVariable(PARAM_DOCUMENT_ID) long aDocumentId)
        throws Exception
    {               
        // Get project (this also ensures that it exists and that the current user can access it
        Project project = getProject(aProjectId);
        
        SourceDocument doc = getDocument(project, aDocumentId);
        
        List<AnnotationDocument> annotations = documentService.listAnnotationDocuments(doc);

        List<RAnnotation> annotationList = new ArrayList<>();
        for (AnnotationDocument annotation : annotations) { 
            annotationList.add(new RAnnotation(annotation));                                 
        }
        
        return ResponseEntity.ok(annotationList);
    }
    
    @ApiOperation(value = "Create annotations for a document in a project")
    @RequestMapping(
            value = "/" + PROJECTS + "/{" + PARAM_PROJECT_ID + "}/" + DOCUMENTS + "/{"
                    + PARAM_DOCUMENT_ID + "}/" + ANNOTATIONS + "/{" + PARAM_ANNOTATOR_ID + "}",
            method = RequestMethod.POST,
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_UTF8_VALUE)
    public ResponseEntity<RAnnotation> annotationsCreate(
            @PathVariable(PARAM_PROJECT_ID) long aProjectId,
            @PathVariable(PARAM_DOCUMENT_ID) long aDocumentId,
            @PathVariable(PARAM_ANNOTATOR_ID) String aAnnotatorId,
            @RequestParam(value = PARAM_FILE) MultipartFile aFile,
            @RequestParam(value = PARAM_FORMAT) Optional<String> aFormat,
            UriComponentsBuilder aUcb) 
        throws Exception
    {
        User annotator = getUser(aAnnotatorId);
        Project project = getProject(aProjectId);
        SourceDocument document = getDocument(project, aDocumentId);
        AnnotationDocument anno = getAnnotation(document, aAnnotatorId, true);
    
        // Check if the format is supported
        String format = aFormat.orElse(FORMAT_DEFAULT);
        Map<String, Class<CollectionReader>> readableFormats = importExportService
                .getReadableFormats();
        if (readableFormats.get(format) == null) {
            throw new UnsupportedFormatException(
                    "Format [%s] not supported. Acceptable formats are %s.", format,
                    readableFormats.keySet());
        }
        
        // Convert the uploaded annotation document into a CAS
        File tmpFile = null;
        JCas annotationCas;
        try {
            tmpFile = File.createTempFile("upload", ".bin");
            aFile.transferTo(tmpFile);
            annotationCas = importExportService.importCasFromFile(tmpFile, project, format);
        }
        finally {
            if (tmpFile != null) {
                FileUtils.forceDelete(tmpFile);
            }
        }
        
        // Check if the uploaded file is compatible with the source document. They are compatible
        // if the text is the same and if all the token and sentence annotations have the same
        // offsets.
        JCas initialCas = documentService.createOrReadInitialCas(document);
        String initialText = initialCas.getDocumentText();
        String annotationText = annotationCas.getDocumentText();
        
        // If any of the texts contains tailing line breaks, we ignore that. We assume at the moment
        // that nobody will have created annotations over that trailing line breaks.
        initialText = StringUtils.chomp(initialText);
        annotationText = StringUtils.chomp(annotationText);
        
        if (ObjectUtils.notEqual(initialText, annotationText)) {
            int diffIndex = StringUtils.indexOfDifference(initialText, annotationText);
            String expected = initialText.substring(diffIndex,
                    Math.min(initialText.length(), diffIndex + 20));
            String actual = annotationText.substring(diffIndex,
                    Math.min(annotationText.length(), diffIndex + 20));
            throw new IncompatibleDocumentException(
                    "Text of annotation document does not match text of source document at offset "
                            + "[%d]. Expected [%s] but found [%s].",
                    diffIndex, expected, actual);
        }
        
        // Just in case we really had to chomp off a trailing line break from the annotation CAS,
        // make sure we copy over the proper text from the initial CAS
        // NOT AT HOME THIS YOU SHOULD TRY
        // SETTING THE SOFA STRING FORCEFULLY FOLLOWING THE DARK SIDE IS!
        forceSetFeatureValue(annotationCas.getSofa(), CAS.FEATURE_BASE_NAME_SOFASTRING,
                initialCas.getDocumentText());
        FSUtil.setFeature(annotationCas.getDocumentAnnotationFs(), CAS.FEATURE_BASE_NAME_END,
                initialCas.getDocumentText().length());
        
        Collection<Sentence> annotationSentences = select(annotationCas, Sentence.class);
        Collection<Sentence> initialSentences = select(initialCas, Sentence.class);
        if (annotationSentences.size() != initialSentences.size()) {
            throw new IncompatibleDocumentException(
                    "Expected [%d] sentences, but annotation document contains [%d] sentences.",
                    initialSentences.size(), annotationSentences.size());
        }
        assertCompatibleOffsets(initialSentences, annotationSentences);
        
        Collection<Token> annotationTokens = select(annotationCas, Token.class);
        Collection<Token> initialTokens = select(initialCas, Token.class);
        if (annotationTokens.size() != initialTokens.size()) {
            throw new IncompatibleDocumentException(
                    "Expected [%d] sentences, but annotation document contains [%d] sentences.",
                    initialSentences.size(), annotationSentences.size());
        }
        assertCompatibleOffsets(initialTokens, annotationTokens);
        
        // If they are compatible, then we can store the new annotations
        documentService.writeAnnotationCas(annotationCas, document, annotator, false);
        
        RAnnotation response = new RAnnotation(anno);
        return ResponseEntity.created(aUcb
                .path(API_BASE + "/" + PROJECTS + "/{pid}/" + DOCUMENTS + "/{did}/" + ANNOTATIONS
                        + "{aid}")
                .buildAndExpand(project.getId(), document.getId(), annotator.getUsername()).toUri())
                .body(response);
    }

    @ApiOperation(value = "Get annotations of a document in a project", response = byte[].class)
    @RequestMapping(
            value = "/" + PROJECTS + "/{" + PARAM_PROJECT_ID + "}/" + DOCUMENTS + "/{"
                    + PARAM_DOCUMENT_ID + "}/" + ANNOTATIONS + "/{" + PARAM_ANNOTATOR_ID + "}",
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> annotationsRead(
            @PathVariable(PARAM_PROJECT_ID) long aProjectId,
            @PathVariable(PARAM_DOCUMENT_ID) long aDocumentId,
            @PathVariable(PARAM_ANNOTATOR_ID) String aAnnotatorId,
            @RequestParam(value = PARAM_FORMAT) Optional<String> aFormat)
        throws Exception
    {               
        // Get project (this also ensures that it exists and that the current user can access it
        Project project = getProject(aProjectId);
                
        SourceDocument doc = getDocument(project, aDocumentId);

        // Check format
        String format;
        if (aFormat.isPresent()) {
            if (VAL_ORIGINAL.equals(aFormat.get())) {
                format = doc.getFormat();
            }
            else {
                format = aFormat.get();
            }
        }
        else {
            format = doc.getFormat();
        }
        
        // Determine the format
        Class<?> writer = importExportService.getWritableFormats().get(format);
        if (writer == null) {
            String msg = "[" + doc.getName() + "] No writer found for format [" + format
                    + "] - exporting as WebAnno TSV instead.";
            LOG.info(msg);
            writer = WebannoTsv3Writer.class;
        }
        
        // In principle we don't need this call - but it makes sure that we check that the
        // annotation document entry is actually properly set up in the database.
        AnnotationDocument anno = getAnnotation(doc, aAnnotatorId, false);
        
        // Create a temporary export file from the annotations
        File exportedAnnoFile = null;
        byte[] resource;
        try {
            exportedAnnoFile = importExportService.exportAnnotationDocument(doc, anno.getUser(),
                    writer, doc.getName(), Mode.ANNOTATION);
            resource = FileUtils.readFileToByteArray(exportedAnnoFile);
        }
        finally {
            if (exportedAnnoFile != null) {
                FileUtils.forceDelete(exportedAnnoFile);
            }
        }

        String filename = FilenameUtils.removeExtension(doc.getName());
        filename += "-" + anno.getUser();
        filename += "." + FilenameUtils.getExtension(doc.getName());
        
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentLength(resource.length);
        httpHeaders.set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        
        return new ResponseEntity<>(resource, httpHeaders, HttpStatus.OK);
    }
    
    private static <T extends AnnotationFS> void assertCompatibleOffsets(Collection<T> aExpected,
            Collection<T> aActual)
        throws IncompatibleDocumentException
    {
        int unitIndex = 0;
        Iterator<T> asi = aExpected.iterator();
        Iterator<T> isi = aActual.iterator();
        // At this point we know that the number of sentences is the same, so it is ok to check only
        // one of the iterators for hasNext()
        while (asi.hasNext()) {
            T as = asi.next();
            T is = isi.next();
            if (as.getBegin() != is.getBegin() || as.getEnd() != is.getEnd()) {
                throw new IncompatibleDocumentException(
                        "Expected %s [%d] to have range [%d-%d], but instead found range "
                                + "[%d-%d] in annotation document.",
                        is.getType().getShortName(), unitIndex, is.getBegin(), is.getEnd(),
                        as.getBegin(), as.getEnd());
            }
            unitIndex++;
        }
    }
    
    private static void forceSetFeatureValue(FeatureStructure aFS, String aFeatureName,
            String aValue)
    {
        CASImpl casImpl = (CASImpl) aFS.getCAS().getLowLevelCAS();
        TypeSystemImpl ts = (TypeSystemImpl) aFS.getCAS().getTypeSystem();
        Feature feat = aFS.getType().getFeatureByBaseName(aFeatureName);
        int featCode = ((FeatureImpl) feat).getCode();
        int thisType = ((TypeImpl) aFS.getType()).getCode();
        if (!ts.isApprop(thisType, featCode)) {
            throw new IllegalArgumentException("Feature structure does not have that feature");
        }
        if (!ts.subsumes(ts.getType(CAS.TYPE_NAME_STRING), feat.getRange())) {
            throw new IllegalArgumentException("Not a string feature!");
        }
        casImpl.ll_setStringValue(casImpl.ll_getFSRef(aFS), featCode, aValue);
    }
}
