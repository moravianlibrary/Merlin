package cz.mzk.osdd.merlin.models;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Created by Jakub Kremlacek on 3.4.17.
 */
public class Title {

    private static final String FILE_K4_SUFFIX = ".xml";
    private static final String FILE_IMAGE_SUFFIX = ".NDK_USER";

    public final String OUTPUT_PACK_PATH;
    public final boolean LOUD;

    public final Path location;

    private String parentUUID;
    private String sysno = null;
    private String base = null;

    private Map<String, ExportPack> packs = new HashMap<>();
    private List<String> notModifiedFOXMLs = null;

    public Title(Path location, File[] files, String outputPackPath, boolean loud) throws IllegalArgumentException, ParserConfigurationException, SAXException, IOException {
        this.OUTPUT_PACK_PATH = outputPackPath;
        this.LOUD = loud;

        this.location = location;

        checkFiles(files);
    }

    public int getPackCount() {
        return packs.size();
    }

    private void checkFiles(File[] files) throws IllegalArgumentException, IOException, SAXException, ParserConfigurationException {
        for (File f : files) {
            String uuid;

            if (f.getName().endsWith(FILE_IMAGE_SUFFIX)) {
                uuid = f.getName().substring(0, f.getName().length() - FILE_IMAGE_SUFFIX.length());

                if (packs.containsKey(uuid)) {
                    packs.get(uuid).setHasImageExport(f.getPath());
                } else {
                    ExportPack p = new ExportPack(uuid);
                    p.setHasImageExport(f.getPath());

                    packs.put(uuid, p);
                }

            } else if (f.getName().endsWith(FILE_K4_SUFFIX)) {
                uuid = f.getName().substring(0, f.getName().length() - FILE_K4_SUFFIX.length());

                if (packs.containsKey(uuid)) {
                    packs.get(uuid).setHasKrameriusExport(f.getPath());
                } else {
                    ExportPack p = new ExportPack(uuid);
                    p.setHasKrameriusExport(f.getPath());

                    packs.put(uuid, p);
                }
            } else if (f.getName().equals("proarc_export_status.log")) {
                continue;
            } else {
                throw new IllegalArgumentException("Unknown file type : " + f.getName());
            }
        }

        List<String> uuidsToRemove = new LinkedList<>();

        for (ExportPack pack : packs.values()) {
            if (!isPage(pack)) {
                uuidsToRemove.add(pack.uuid);

                parentUUID = pack.uuid;

                if (pack.hasImageExport()) throw new IllegalArgumentException("UUID: " + pack.uuid + " contains image data for non data FOXML type!");
                continue;
            }

            if (!pack.hasImageExport() || !pack.hasKrameriusExport()) {
                String msg;

                if (!pack.hasKrameriusExport()) {
                    msg = "FOXML";
                } else {
                    msg = "Image";
                }

                throw new IllegalArgumentException("Part " + pack.uuid + " is not valid. Missing " + msg + " part.");
            }
        }

        for (String uuid : uuidsToRemove) {
            packs.remove(uuid);
        }

        notModifiedFOXMLs = uuidsToRemove;

        if (LOUD) {
            System.out.println("Title " + parentUUID + " filecheck completed.");
        }
    }

    private boolean isPage(ExportPack pack) throws ParserConfigurationException, IOException, SAXException {
        Document doc = Utils.getDocumentFromFile(pack.getKrameriusExportPath().toFile());

        NodeList elements = doc.getElementsByTagName("datastream");

        for (int i = 0; i < elements.getLength(); i++) {
            if (((Element) elements.item(i)).getAttribute("ID").equals("IMG_FULL")) return true;
        }

        return false;
    }

    private static void reportMissingPart(ExportPack pack, String missingPart) {
        System.err.println("Pack " + pack.uuid + missingPart + ". Terminating.");
    }

    public void processTitle(Path outRoot) throws IOException, ParserConfigurationException, SAXException, IllegalArgumentException {

        if (LOUD) System.out.println("Started processing title " + parentUUID);

        if (LOUD) System.out.println("Receiving Sysno from Aleph");
        Pair<String, String> sb = Utils.getSysnoWithBaseFromAleph(Utils.getSignatureFromRootObject(this.location));

        if (sb == null) {
            System.err.println("Could not receive Sysno and Base from Aleph. Skipping title at: " + location);
            return;
        } else if (LOUD){
            System.out.println("Received Sysno from Aleph");
        }

        sysno = sb.first;
        base = sb.second.toLowerCase();

        Path outFoxml = outRoot.resolve(OUTPUT_PACK_PATH).resolve("kramerius").resolve(parentUUID);
        if (!outFoxml.toFile().exists()) outFoxml.toFile().mkdirs();

        Path imsDirectory = outRoot.resolve(OUTPUT_PACK_PATH).resolve("imageserver").resolve(base).resolve(sysno.substring(0, 3)).resolve(sysno.substring(3, 6)).resolve(sysno.substring(6, sysno.length()));

        if (!imsDirectory.toFile().exists()) imsDirectory.toFile().mkdirs();

        for (ExportPack pack : packs.values()) {

            Path imagePath = imsDirectory.resolve(pack.uuid + ".jp2");

            Foxml f = new Foxml(Utils.getDocumentFromFile(pack.getKrameriusExportPath().toFile()), sysno, base, pack.uuid);

            try {
                f.processDatastream(Foxml.DATASTREAM_IMG_FULL);
                f.processDatastream(Foxml.DATASTREAM_IMG_THUMB);
                f.processDatastream(Foxml.DATASTREAM_IMG_PREVIEW);
                f.processRDF();
                f.save(outFoxml);
            } catch (IllegalArgumentException e) {
                reportMissingPart(pack, e.getMessage());
                return;
            } catch (TransformerException e) {
                System.err.println("Cannot save " + pack.uuid + ".xml. Terminating.");
                e.printStackTrace();
                return;
            }

            Files.copy(pack.getImageExportPath(), imagePath);
        }

        for (String foxml : notModifiedFOXMLs) {
            Files.copy(location.resolve(foxml + ".xml"), outFoxml.resolve(foxml + ".xml"));
        }

        if (LOUD) System.out.println("Title " + parentUUID + " processed.");
    }
}
