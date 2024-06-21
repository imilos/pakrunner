/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package rs.mivanovic.pakrunner;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.File;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import java.io.FileInputStream;

import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 *
 * @author milos
 */
@RestController
@RequestMapping("pakrunner")
public class PakREST {

    @Value("${KILL_PROCESSES_COMMAND}")
    public String KILL_PROCESSES_COMMAND;
    @Value("${LOG_FILE}")
    public String LOG_FILE;
    @Value("${RESULT_ZIP}")
    public String RESULT_ZIP;
    @Value("${MASTER_DIR}")
    public String MASTER_DIR;
    @Value("${RESULT_DIR}")
    public String WORKING_DIR_ROOT;

    // Promenjeno da ne bude final
    private static String GUID = "1111-1111-22222-33333";
    private static String COMMAND = "./pakv.run";

    private static Process process = null;
    private static long startTime = System.nanoTime();
    private static boolean previousStatus = false;

    /**
     * Vraca isti JSON koji mu je poslat. Ako ne moze da parsira JSON, vraca
     * gresku
     *
     * @param input
     * @return
     */
    @PostMapping("/echo")
    @Operation(
            summary = "Echo vraća string koji mu je poslat.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Vraća isti string koji mu je poslat.",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "CustomRequest",
                                    value = "{\"guid\":\"3333-5555\", \"command\":\"./proba.sh\"}"
                            )
                    )
            )
    )
    public ResponseEntity echo(@RequestBody String input) {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();
        JsonNode jsonNode;

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            jsonNode = objectMapper.readTree(input);

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "ERROR: I/O Exception in the service: " + e.getMessage());
            return ResponseEntity.ok(json.toString());
        }

        return ResponseEntity.ok(jsonNode.toString());
    }

    /**
     *
     * @param input
     * @return
     */
    @PostMapping("/createnew")
    @Operation(
            summary = "Kreiranje novog posla",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Osnovna jedinica rada je posao, koji se kreira pozivom /createnew, kopiranjem sadržaja direktorijuma MASTER_DIR "
                    + "u direktorijum RESULT_DIR/GUID. Kopiranje direktorijuma NIJE rekurzivno. Ukoliko dati posao već postoji, nastaje greška. "
                    + "Kreiranje posla NE POKREĆE proračun, već ga samo priprema.",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "CustomRequest",
                                    value = "{\"guid\":\"3333-4444\"}"
                            )
                    )
            )
    )
    public ResponseEntity createNewTask(@RequestBody String input) {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        try {

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(input);

            // Proveri da li je JSON u pravom formatu i setuj varijable
            if (!jsonNode.has("guid")) {
                throw new RuntimeException("ERROR: JSON format problem");
            } else {
                GUID = jsonNode.get("guid").asText();
            }

            String workdir = WORKING_DIR_ROOT + File.separator + GUID;
            File workingDirectory = new File(workdir);

            // Ako direktorijum GUID vec postoji, lansiraj izuzetak
            if (Files.exists(workingDirectory.toPath())) {
                throw new IOException("Directory " + GUID + " already exists.");
            }

            // Iskopiraj sadrzaj mastera
            //FileUtils.copyDirectory(new File(MASTER_DIR), workingDirectory);
            PakUtil.copyDirectoryNoRecursive(new File(MASTER_DIR), workingDirectory);

            // Setuj executable dozvole
            PakUtil.directorySetExecutable(workdir);

        } catch (RuntimeException e) {
            json.put("status", false);
            json.put("message", "ERROR: JSON format problem.");
            return ResponseEntity.ok(json.toString());

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "ERROR: I/O Exception in the service: " + e.getMessage());
            return ResponseEntity.ok(json.toString());
        }

        json.put("status", true);
        json.put("message", "OK - task " + GUID + " created.");

        return ResponseEntity.ok(json.toString());
    }

    /**
     * Pokrece proracun
     *
     * @param input
     * @return
     */
    @PostMapping("/start")
    @Operation(
            summary = "Pokreće kreirani posao",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Kreiran posao se ne pokreće automatski. Potrebno je uz GUID navesti i komandu koja pokreće proračun",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "CustomRequest",
                                    value = "{\"guid\":\"3333-4444\", \"command\":\"./proba.sh\"}"
                            )
                    )
            )
    )
    public ResponseEntity startService(@RequestBody String input) {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        try {
            // Ako je vec aktivan, prvo ubij proces
            stopService(GUID);

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(input);

            // Proveri da li je JSON u pravom formatu i setuj varijable
            if (!jsonNode.has("guid") || !jsonNode.has("command")) {
                throw new RuntimeException("ERROR: JSON format problem");
            } else {
                GUID = jsonNode.get("guid").asText();
                COMMAND = jsonNode.get("command").asText();
            }

            String workdir = WORKING_DIR_ROOT + File.separator + GUID;
            File workingDirectory = new File(workdir);

            // Ako ne postoji dati GUID, lanisraj IO izuzetak
            if (!Files.exists(workingDirectory.toPath())) {
                throw new IOException("Task " + GUID + " does not exist.");
            }

            File logFile = new File(workdir + File.separator + LOG_FILE);
            Files.deleteIfExists(logFile.toPath());

            String[] commands = COMMAND.split("\\s+");
            ProcessBuilder pb = new ProcessBuilder(commands);
            pb.directory(workingDirectory);
            pb.redirectErrorStream(true);
            pb.redirectOutput(Redirect.appendTo(logFile));
            process = pb.start();
            startTime = System.nanoTime();

        } catch (RuntimeException e) {
            json.put("status", false);
            json.put("message", "ERROR: JSON format problem.");
            return ResponseEntity.ok(json.toString());

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "ERROR: I/O Exception in the service: " + e.getMessage());
            return ResponseEntity.ok(json.toString());
        }

        json.put("status", true);
        json.put("message", "OK - task " + GUID + " started.");

        return ResponseEntity.ok(json.toString());
    }

    /**
     *
     * @param input
     * @return
     */
    @PostMapping("/stop")
    @Operation(
            summary = "Zaustavljanje posla",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Posao se može u svakom trenutku zaustaviti jednostavnim pozivom. "
                    + "Terminacija procesa implicira i terminaciju procesa koje je osnovni proces eventualno pokrenuo.",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "CustomRequest",
                                    value = "{\"guid\":\"3333-4444\"}"
                            )
                    )
            )
    )
    public ResponseEntity stopService(@RequestBody String input) {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        try {
            // Ubij glavni proces
            if (process != null) {
                try {
                    process.destroy();
                    startTime = System.nanoTime();
                } catch (Exception e) {
                    json.put("status", false);
                    json.put("message", "ERROR: Cannot kill process.");

                    return ResponseEntity.ok(json.toString());
                }
            }

            // Ubij sve procese koji u nazivu radnog direktorijuma imaju GUID
            String command = KILL_PROCESSES_COMMAND + " " + GUID;
            String[] commands = command.split("\\s+");
            String workdir = WORKING_DIR_ROOT + File.separator + GUID;

            ProcessBuilder pb = new ProcessBuilder(commands);
            pb.directory(new File(workdir));
            pb.redirectErrorStream(true);
            pb.redirectOutput(Redirect.appendTo(new File(workdir + File.separator + LOG_FILE)));
            pb.start();

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "ERROR: IO problem in the service.");
            return ResponseEntity.ok(json.toString());
        }

        json.put("status", true);
        json.put("message", "OK");

        return ResponseEntity.ok(json.toString());
    }

    /**
     * Da li proracun sa datim GUID-om trenutno radi?
     *
     * @param guidInput
     * @return
     */
    @GetMapping("/isrunning/{guid}")
    @Operation(
            summary = "Upit statusa posla.",
            parameters = {
                @Parameter(
                        name = "guid",
                        description = "GUID posla",
                        example = "1111-4444"
                )
            }
    )
    public ResponseEntity isRunning(@PathVariable("guid") String guidInput) {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();
        boolean status = false;

        // Ako je proces ziv i poslati guid odgovara globalnom GUID-u
        if (process != null && process.isAlive() && guidInput.equals(GUID)) {
            status = true;
        }

        if (previousStatus != status) {
            previousStatus = status;
            startTime = System.nanoTime();
        }

        json.put("status", status);
        json.put("time", TimeUnit.SECONDS.convert(System.nanoTime() - startTime, TimeUnit.NANOSECONDS));

        return ResponseEntity.ok(json.toString());
    }

    /**
     * Vraca GUID proracuna ako proracun radi
     *
     * @return
     */
    @GetMapping("/runningtask")
    @Operation(
            summary = "Trenutno aktivan posao. Vraća se GUID trenutno aktivnog posla i status true u slučaju da bilo koji proračun trenutno radi. "
            + "U suprotnom se vraća status false i prazan string za GUID.",
            parameters = {}
    )
    public ResponseEntity runningGuid() {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        // Ako je proces ziv i poslati guid odgovara globalnom GUID-u
        if (process != null && process.isAlive()) {
            json.put("status", true);
            json.put("GUID", GUID);
        } else {
            json.put("status", false);
            json.put("GUID", "");
        }

        return ResponseEntity.ok(json.toString());
    }

    /**
     * Vraca poslednjih 'lines' linija loga. Ako je lines==0, vraca sve.
     *
     * @param guid
     * @param lines
     * @return
     */
    @GetMapping("/logtail/{guid}/{lines}")
    @Operation(
            summary = "Poslednjih n linija loga za posao GUID. Ako je n=0, preuzima se ceo log.",
            parameters = {
                @Parameter(
                        name = "guid",
                        description = "GUID posla",
                        example = "1111-4444"
                ),
                @Parameter(
                        name = "lines",
                        description = "Broj linija.",
                        example = "10"
                )
            }
    )
    public String[] getLogTail(@PathVariable("guid") String guid, @PathVariable("lines") int lines) {

        // Ako je lines==0, salji ceo log
        if (lines == 0) {
            lines = Integer.MAX_VALUE;
        }

        String workdir = WORKING_DIR_ROOT + File.separator + guid;

        return PakUtil.tail(new File(workdir + File.separator + LOG_FILE), lines);
    }

    /**
     *
     * @param guidInput
     * @return
     * @throws IOException
     */
    @GetMapping("/logdownload/{guid}")
    @Operation(
            summary = "Preuzimanje loga",
            parameters = {
                @Parameter(
                        name = "guid",
                        description = "GUID taska",
                        example = "111-4444"
                )
            }
    )
    public ResponseEntity downloadLog(@PathVariable("guid") String guidInput) throws IOException {

        String workdir = WORKING_DIR_ROOT + File.separator + guidInput;

        File log = new File(workdir + File.separator + LOG_FILE);

        if (log.isFile()) {

            InputStreamResource resource = new InputStreamResource(new FileInputStream(log));

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=" + log.getName())
                    .contentType(MediaType.TEXT_PLAIN)
                    .contentLength(log.length())
                    .body(resource);
        }

        return new ResponseEntity("Nije pronadjen log!", HttpStatus.NOT_FOUND);
    }

    /**
     * Vraca ZIP sa trazenim fajlovima
     *
     * @param input
     * @return
     */
    @PostMapping("/getresults")
    @Operation(
            summary = "Preuzimanje rezultata posla",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Rezultati (ili bilo koji fajlovi u argumentu files) iz nekog posla se mogu preuzeti u formi priloga pomoću poziva.",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "CustomRequest",
                                    value = "{\"guid\":\"3333-4444\", \"files\":[\"proba.sh\",\"pak.log\"]}"
                            )
                    )
            )
    )
    public ResponseEntity getResults(@RequestBody String input) {

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(input);

            // Iscitaj i setuj GUID
            GUID = jsonNode.get("guid").asText();

            // Iscitaj listu trazenih fajlova iz JSON-a
            String filesJSON = jsonNode.get("files").toString();
            List<String> fileNames = objectMapper.readValue(filesJSON, List.class);

            // Konvertuj listu u niz stringova
            String[] fileNamesArray = fileNames.toArray(String[]::new);

            String workdir = WORKING_DIR_ROOT + File.separator + GUID;

            if (fileNamesArray.length != 0 && PakUtil.zipFiles(workdir, fileNamesArray, RESULT_ZIP)) {

                File zipFile = new File(workdir + File.separator + RESULT_ZIP);

                if (zipFile.isFile()) {
                    InputStreamResource resource = new InputStreamResource(new FileInputStream(zipFile));

                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=" + zipFile.getName())
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .contentLength(zipFile.length())
                            .body(resource);
                }
            }

        } catch (IOException e) {
            return new ResponseEntity("ERROR: I/O problem!", HttpStatus.NOT_FOUND);
        }

        return new ResponseEntity("ERROR: ZIP file not found!", HttpStatus.NOT_FOUND);
    }

    /**
     * Brisanje posla sa poslatim GUID-om
     *
     * @param guidInput
     * @return
     */
    @GetMapping("/remove/{guid}")
    @Operation(
            summary = "Uklanjanje posla. Ceo posao se može ukloniti. Pre toga se zaustavlja ukoliko je proračun bio aktivan. ",
            parameters = {
                @Parameter(
                        name = "guid",
                        description = "GUID taska",
                        example = "3333-4444"
                )
            }
    )
    public ResponseEntity remove(@PathVariable("guid") String guidInput) {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        // Ako je nalog za brisanje poslat tekucem GUID-u, prvo stopiraj proracun
        if (guidInput.equals(GUID)) {
            stopService(GUID);
        }

        File dirToRemove = new File(WORKING_DIR_ROOT + File.separator + guidInput);

        try {
            if (Files.exists(dirToRemove.toPath())) {
                FileUtils.deleteDirectory(dirToRemove);
                json.put("status", true);
                json.put("message", "Directory " + guidInput + " removed.");
            } else {
                json.put("status", true);
                json.put("message", "Directory " + guidInput + " does not exist.");
            }

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "Directory " + guidInput + " not removed.");
        }

        return ResponseEntity.ok(json.toString());
    }

    /**
     * Brise sve poslove u WORKING_DIR_ROOT
     *
     * @return
     */
    @GetMapping("/removeall")
    @Operation(
            summary = "Brisanje svih poslova",
            parameters = {}
    )
    public ResponseEntity removeAll() {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        // Ako je nalog za brisanje svih poslat, prvo stopiraj proracun
        stopService(GUID);

        try {
            FileUtils.cleanDirectory(new File(WORKING_DIR_ROOT));
            json.put("status", true);
            json.put("message", "Directory " + WORKING_DIR_ROOT + " cleaned.");

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "Directory " + WORKING_DIR_ROOT + " not cleaned.");
        }

        return ResponseEntity.ok(json.toString());
    }

    /**
     *
     * @param uploadedInputStream
     * @param guidInput
     * @param modelMap
     * @return
     */
    @PostMapping("/uploadzip")
    @Operation(
            summary = "Upload ZIP-a u posao. Swagger (OpenAPI) UI ne radi za ovaj poziv. pogledati curl primer.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Upload i raspakovavanje ZIP fajla u radni direktorijum posla GUID",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "CustomRequest",
                                    value = "curl -F 'file=@proba.zip' -F 'guid=3333-1111' -X POST http://localhost:8080/pakrunner/uploadzip"
                            )
                    )
            )
    )
    public ResponseEntity uploadZIP(@RequestParam("file") MultipartFile uploadedInputStream,
            @RequestParam("guid") String guidInput, ModelMap modelMap) {

        modelMap.addAttribute("guid", guidInput);
        modelMap.addAttribute("file", uploadedInputStream);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        File uploadedZIP = new File(WORKING_DIR_ROOT + File.separator + guidInput + File.separator + "tmp.zip");

        try {

            // Obrisi ako vec postoji
            if (uploadedZIP.exists()) {
                uploadedZIP.delete();
            }

            // Snimi fajl iz stream-a
            PakUtil.saveToFile(uploadedInputStream.getInputStream(), uploadedZIP.getPath());

            // Raspakuj fajl
            PakUtil.unzip(uploadedZIP.getPath(), WORKING_DIR_ROOT + File.separator + guidInput);

            // Obrisi privremeni zip na kraju posla
            if (uploadedZIP.exists()) {
                uploadedZIP.delete();
            }

            json.put("status", true);
            json.put("message", "File " + uploadedZIP.getPath() + " extracted into " + guidInput);

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "ERROR: I/O problem.");
        }

        return ResponseEntity.ok(json.toString());
    }

    /**
     * Kopiranje fajla iz podfoldera mastera u radni folder za dati task GUID
     *
     * @param input
     * @return
     */
    @PostMapping("/localcopy")
    @Operation(
            summary = "Kopiranje fajla",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Kopiranje se vrši iz osnovnog MASTER_DIR ili nekog njegovog podfoldera u radni direktorijum posla GUID. "
                    + "Navodi se relativna putanja fajla koji se kopira i ciljno ime fajla",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "CustomRequest",
                                    value = "{\"guid\":\"3333-1111\", \"path\":\"L10/ttt.txt\", \"name\":\"ttt-kopija.txt\"}"
                            )
                    )
            )
    )
    public ResponseEntity localCopy(@RequestBody String input) {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        String guidInput, sourceRelativePath, destName;

        try {

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(input);

            // Proveri da li je JSON u pravom formatu i setuj varijable
            if (!jsonNode.has("guid") || !jsonNode.has("path") || !jsonNode.has("name")) {
                throw new RuntimeException("ERROR: JSON format problem");
            } else {
                guidInput = jsonNode.get("guid").asText();
                sourceRelativePath = jsonNode.get("path").asText();
                destName = jsonNode.get("name").asText();
            }

            File destFile = new File(WORKING_DIR_ROOT + File.separator + guidInput + File.separator + destName);

            // Obrisi ako vec postoji
            if (destFile.exists()) {
                destFile.delete();
            }

            // Kopiranje samog fajla
            FileUtils.copyFile(new File(MASTER_DIR + File.separator + sourceRelativePath), destFile);

            // Setuj odgovarajuce dozvole
            PakUtil.setPermissions(destFile.toString());

        } catch (RuntimeException e) {
            json.put("status", false);
            json.put("message", "ERROR: JSON format problem.");
            return ResponseEntity.ok(json.toString());

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "ERROR: I/O Exception in the service.");
            return ResponseEntity.ok(json.toString());
        }

        json.put("status", true);
        json.put("message", "OK");

        return ResponseEntity.ok(json.toString());
    }

    /**
     * Kopiranje fajla iz radnog direktorijuma guidsrc u direktorijum guiddest
     *
     * @param input
     * @return
     */
    @PostMapping("/copyfiletasktotask")
    @Operation(
            summary = "Kopiranje fajla iz jednog posla u drugi",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Kopiranje se vrši iz radnog direktorijuma guidsrc u direktorijum guiddest, "
                    + "pri čemu je moguće zadati i novo ime fajla.",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "CustomRequest",
                                    value = "{\"guidsrc\":\"3333-1111\", \"guiddest\":\"3333-2222\", \"namesrc\":\"pom.xml\", \"namedest\":\"pom.xml\"}"
                            )
                    )
            )
    )
    public ResponseEntity copyTaskToTask(@RequestBody String input) {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        String guidSrc;
        String guidDest;
        String nameSrc;
        String nameDest;

        try {

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(input);

            // Proveri da li je JSON u pravom formatu i setuj varijable
            if (!jsonNode.has("guidsrc") || !jsonNode.has("guiddest") || !jsonNode.has("namesrc") || !jsonNode.has("namedest")) {
                throw new RuntimeException("ERROR: JSON format problem.");
            } else {
                guidSrc = jsonNode.get("guidsrc").asText();
                guidDest = jsonNode.get("guiddest").asText();
                nameSrc = jsonNode.get("namesrc").asText();
                nameDest = jsonNode.get("namedest").asText();
            }

            File srcFile = new File(WORKING_DIR_ROOT + File.separator + guidSrc + File.separator + nameSrc);
            File destFile = new File(WORKING_DIR_ROOT + File.separator + guidDest + File.separator + nameDest);

            // Obrisi ako vec postoji
            if (destFile.exists()) {
                destFile.delete();
            }

            // Kopiranje samog fajla
            FileUtils.copyFile(srcFile, destFile);

        } catch (RuntimeException e) {
            json.put("status", false);
            json.put("message", "ERROR: JSON format problem.");
            return ResponseEntity.ok(json.toString());

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "ERROR: I/O Exception in the service.");
            return ResponseEntity.ok(json.toString());
        }

        json.put("status", true);
        json.put("message", "OK");

        return ResponseEntity.ok(json.toString());
    }

    /**
     * Brisanje fajla iz radnog direktorijuma taska (radi i za relativne
     * putanje)
     *
     * @param input
     * @return
     */
    @PostMapping("/removefile")
    @Operation(
            summary = "Brisanje fajla iz radnog direktorijuma",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "U svrhu brisanja fajla iz radnog direktorijuma posla, koristi se ovaj poziv. Može se navesti i relativna putanja.",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "CustomRequest",
                                    value = "{\"guid\":\"3333-1111\", \"path\":\"ttt.txt\"}"
                            )
                    )
            )
    )
    public ResponseEntity removeFile(@RequestBody String input) {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        String guidInput, fileRelativePath;

        try {

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(input);

            // Proveri da li je JSON u pravom formatu i setuj varijable
            if (!jsonNode.has("guid") || !jsonNode.has("path")) {
                throw new RuntimeException("ERROR: JSON format problem");
            } else {
                guidInput = jsonNode.get("guid").asText();
                fileRelativePath = jsonNode.get("path").asText();
            }

            String workdir = WORKING_DIR_ROOT + File.separator + guidInput;
            File destFile = new File(workdir + File.separator + fileRelativePath);

            // Obrisi fajl ako postoji
            if (destFile.exists()) {
                destFile.delete();
            } else {
                json.put("status", true);
                json.put("message", "File " + destFile.getPath() + " does not exist.");
                return ResponseEntity.ok(json.toString());
            }

        } catch (RuntimeException e) {
            json.put("status", false);
            json.put("message", "ERROR: JSON format problem.");
            return ResponseEntity.ok(json.toString());

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "ERROR: I/O Exception in the service.");
            return ResponseEntity.ok(json.toString());
        }

        json.put("status", true);
        json.put("message", "OK");

        return ResponseEntity.ok(json.toString());
    }

    /**
     * Preimenovanje fajla iz radnog direktorijuma
     *
     * @param input
     * @return
     */
    @PostMapping("/renamefile")
    @Operation(
            summary = "Preimenovanje fajla u radnom direktorijumu",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "U svrhu preimenovanja fajla iz radnog direktorijuma posla, koristi se ovaj poziv. Mogu se navesti i relativne putanje.",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "CustomRequest",
                                    value = "{\"guid\":\"3333-1111\", \"pathold\":\"Ulaz.csv\", \"pathnew\":\"Ulaz1.csv\"}"
                            )
                    )
            )
    )
    public ResponseEntity renameFile(@RequestBody String input) {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        String guidInput;
        String pathOld;
        String pathNew;

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(input);

            // Proveri da li je JSON u pravom formatu i setuj varijable
            if (!jsonNode.has("guid")) {
                throw new RuntimeException("ERROR: JSON format problem (guid).");
            }
            if (!jsonNode.has("pathold")) {
                throw new RuntimeException("ERROR: JSON format problem (pathold).");
            }
            if (!jsonNode.has("pathnew")) {
                throw new RuntimeException("ERROR: JSON format problem (pathnew).");
            }

            guidInput = jsonNode.get("guid").asText();
            pathOld = jsonNode.get("pathold").asText();
            pathNew = jsonNode.get("pathnew").asText();

            File oldFile = new File(WORKING_DIR_ROOT + File.separator + guidInput + File.separator + pathOld);
            File newFile = new File(WORKING_DIR_ROOT + File.separator + guidInput + File.separator + pathNew);

            // Obrisi ako vec postoji
            if (oldFile.exists()) {
                oldFile.renameTo(newFile);
            } else {
                json.put("status", false);
                json.put("message", "ERROR: File " + oldFile.getPath() + " does not exist.");
                return ResponseEntity.ok(json.toString());
            }

        } catch (RuntimeException e) {
            json.put("status", false);
            json.put("message", e.getMessage());
            return ResponseEntity.ok(json.toString());

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "ERROR: I/O Exception in the service: " + e.getMessage());
            return ResponseEntity.ok(json.toString());
        }

        json.put("status", true);
        json.put("message", "OK");

        return ResponseEntity.ok(json.toString());
    }

    /**
     * Vraca listu fajlova iz radnog direktorijuma guid + path
     *
     * @param input
     * @return
     */
    @PostMapping("/listfiles")
    @Operation(
            summary = "Vraća listu fajlova",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Vraća listu fajlova iz radnog direktorijuma guid + path",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "CustomRequest",
                                    value = "{\"guid\":\"3333-4444\", \"path\":\"/L10\"}"
                            )
                    )
            )
    )
    public ResponseEntity listfiles(@RequestBody String input) {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        String guidInput;
        String relativePath;
        String dirToList;

        try {

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(input);

            // Proveri da li je JSON u pravom formatu i setuj varijable
            if (!jsonNode.has("guid") || !jsonNode.has("path")) {
                throw new RuntimeException("ERROR: JSON format problem");
            } else {
                guidInput = jsonNode.get("guid").asText();
                relativePath = jsonNode.get("path").asText();
            }

            // Ako je relativePath prazan, vraca radni dir, a ako nije vraca MASTER_DIR + relativePath
            if (relativePath.isEmpty()) {
                dirToList = WORKING_DIR_ROOT + File.separator + guidInput + File.separator + relativePath;
            } else {
                dirToList = MASTER_DIR + File.separator + relativePath;
            }

            // Pronadji sve fajlove i direktorijume na zadatoj putanji
            List<java.nio.file.Path> listOfPaths = Files
                    .find(Paths.get(dirToList), 1, (path, attr) -> true)
                    .collect(Collectors.toList());

            ArrayList<String> fileList = new ArrayList<>();
            ArrayList<String> dirList = new ArrayList<>();

            // Stavi fajlove i direktorijume u posebne liste
            for (java.nio.file.Path p : listOfPaths) {
                if (p.toFile().isFile()) {
                    fileList.add(p.getFileName().toString());
                } else if (p.toFile().isDirectory()) {
                    dirList.add(p.getFileName().toString());
                }
            }

            // Ukloni prvi clan koji je naziv roditeljskog direktorijuma
            dirList.remove(0);

            json.put("status", true);
            json.put("message", "OK");
            json.put("task", guidInput);
            json.put("path", relativePath);

            ArrayNode dirsNode = mapper.createArrayNode();

            for (String item : dirList) {
                dirsNode.add(item);
            }

            json.set("directories", dirsNode);

            ArrayNode filesNode = mapper.createArrayNode();

            for (String item : fileList) {
                filesNode.add(item);
            }

            json.set("files", filesNode);

            return ResponseEntity.ok(json.toString());

        } catch (RuntimeException e) {
            json.put("status", false);
            json.put("message", "ERROR: JSON format problem.");
            return ResponseEntity.ok(json.toString());

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "ERROR: I/O Exception in the service.");
            return ResponseEntity.ok(json.toString());
        }
    }

    /**
     * Vraca listu taskova
     *
     * @return
     */
    @GetMapping("/tasklist")
    @Operation(
            summary = "Lista poslova. Lista tekućih poslova u direktorijumu RESULT_DIR",
            parameters = {}
    )
    public ResponseEntity taskslist() {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        try {

            // Pronadji sve fajlove i direktorijume na zadatoj putanji
            List<java.nio.file.Path> listOfPaths = Files
                    .find(Paths.get(WORKING_DIR_ROOT), 1, (path, attr) -> path.toFile().isDirectory())
                    .collect(Collectors.toList());

            ArrayList<String> dirList = new ArrayList<>();

            // Stavi fajlove i direktorijume u posebne liste
            listOfPaths.forEach(p -> {
                dirList.add(p.getFileName().toString());
            });

            // Ukloni roditeljski direktorijum koji stavlja na prvo mesto
            dirList.remove(0);

            json.put("status", true);
            json.put("message", "OK");

            ArrayNode dirsNode = mapper.createArrayNode();

            for (String item : dirList) {
                dirsNode.add(item);
            }

            json.set("tasks", dirsNode);

            return ResponseEntity.ok(json.toString());

        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "ERROR: I/O Exception in the service.");
            return ResponseEntity.ok(json.toString());
        }
    }

    /**
     * Koristi se za pokretanje raznih pomocnih skriptova koji kratko traju
     *
     * @param input
     * @return
     */
    @PostMapping("/runshorttask")
    @Operation(
            summary = "Izvršavanje kratkog posla",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Ovaj poziv se koristi za razne pomoćne skriptove (kopiranje, brisanje, promena prava pristupa...) "
                    + "za koje se ne očekuje da dugo traju. Poziv je blokirajući, a ovakvi pozivi se loguju u poseban log fajl "
                    + "pod nazivom shorttask.log.",
                    content = @Content(
                            examples = @ExampleObject(
                                    name = "CustomRequest",
                                    value = "{\"guid\":\"3333-4444\", \"command\":\"./proba1.sh\"}"
                            )
                    )
            )
    )
    public ResponseEntity runShortTask(@RequestBody String input) {

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();

        String guid, command;

        try {

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(input);

            // Proveri da li je JSON u pravom formatu i setuj varijable
            if (!jsonNode.has("guid") || !jsonNode.has("command")) {
                throw new RuntimeException("ERROR: JSON format problem");
            } else {
                guid = jsonNode.get("guid").asText();
                command = jsonNode.get("command").asText();
            }

            String workdir = WORKING_DIR_ROOT + File.separator + guid;
            File workingDirectory = new File(workdir);

            // Ako ne postoji dati GUID, lanisraj IO izuzetak
            if (!Files.exists(workingDirectory.toPath())) {
                throw new IOException("Task " + guid + " does not exist.");
            }

            // Loguje se u poseban shorttask.log fajl
            File logFile = new File(workdir + File.separator + "shorttask.log");

            String[] commands = command.split("\\s+");
            ProcessBuilder pb = new ProcessBuilder(commands);
            pb.directory(workingDirectory);
            pb.redirectErrorStream(true);
            pb.redirectOutput(Redirect.appendTo(logFile));
            Process shortTask = pb.start();
            shortTask.waitFor();

        } catch (RuntimeException e) {
            json.put("status", false);
            json.put("message", "ERROR: JSON format problem.");
            return ResponseEntity.ok(json.toString());
        } catch (IOException e) {
            json.put("status", false);
            json.put("message", "ERROR: I/O Exception in the service: " + e.getMessage());
            return ResponseEntity.ok(json.toString());
        } catch (InterruptedException e) {
            json.put("status", false);
            json.put("message", "ERROR: Process interupted: " + e.getMessage());
            return ResponseEntity.ok(json.toString());
        }

        json.put("status", true);
        json.put("message", "OK - " + command + " within task " + guid + " completed.");

        return ResponseEntity.ok(json.toString());
    }

}
