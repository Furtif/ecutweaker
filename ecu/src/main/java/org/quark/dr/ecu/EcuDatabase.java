package org.quark.dr.ecu;

import android.os.Environment;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class EcuDatabase {
    private static ProjectData.Projects Projects = null;
    private final HashMap<Integer, ArrayList<EcuInfo>> m_ecuInfo;
    private final HashMap<Integer, String> m_ecuAddressing;
    private final HashMap<Integer, String> RXADDRMAP, TXADDRMAP, RXADDRMAP_EXT, TXADDRMAP_EXT;
    private final HashMap<String, String> MODELSMAP;
    public String current_project_code;
    public String current_project_name;
    boolean m_loaded;
    private Set<String> m_projectSet;
    private String m_ecuFilePath;
    private ZipFileSystem m_zipFileSystem;

    public EcuDatabase() {
        m_loaded = false;
        m_ecuInfo = new HashMap<>();
        m_ecuAddressing = new HashMap<>();
        MODELSMAP = new HashMap<>();
        RXADDRMAP = new HashMap<>();
        TXADDRMAP = new HashMap<>();
        RXADDRMAP_EXT = new HashMap<>();
        TXADDRMAP_EXT = new HashMap<>();
        loadProjectsData();
        buildMaps("ALL");
        loadModels();
    }

    public ArrayList<EcuInfo> getEcuInfo(int addr) {
        return m_ecuInfo.get(addr);
    }

    public ArrayList<String> getEcuByFunctions() {
        ArrayList<String> list = new ArrayList<>();
        Iterator<String> valueIterator = m_ecuAddressing.values().iterator();
        while (valueIterator.hasNext()) {
            list.add(valueIterator.next());
        }
        return list;
    }

    @Nullable
    public EcuInfo identifyOldEcu(int addressId, String supplier, String soft_version, String version, int diag_version) {
        ArrayList<EcuInfo> ecuInfos = m_ecuInfo.get(addressId);
        if (ecuInfos == null)
            return null;
        EcuIdent closestEcuIdent = null;
        EcuInfo keptEcuInfo = null;
        for (EcuInfo ecuInfo : ecuInfos) {
            for (EcuIdent ecuIdent : ecuInfo.ecuIdents) {
                if (ecuIdent.supplier_code.equals(supplier) && ecuIdent.soft_version.equals(soft_version)) {
                    if (ecuIdent.version.equals(version) && diag_version == Integer.parseInt(ecuIdent.diagnostic_version, 10)) {
                        ecuInfo.exact_match = true;
                        return ecuInfo;
                    }
                    if (closestEcuIdent == null) {
                        closestEcuIdent = ecuIdent;
                        continue;
                    }
                    int intVersion = Integer.parseInt(version, 16);
                    int currentDiff = Math.abs(
                            Integer.parseInt(ecuIdent.version, 16) - intVersion);
                    int oldDiff = Math.abs(Integer.parseInt(
                            closestEcuIdent.version, 16) - intVersion);
                    if (currentDiff < oldDiff) {
                        closestEcuIdent = ecuIdent;
                        keptEcuInfo = ecuInfo;
                    }
                }
            }
        }
        if (keptEcuInfo != null)
            keptEcuInfo.exact_match = false;
        return keptEcuInfo;
    }

    public ArrayList<EcuInfo> identifyNewEcu(EcuIdentifierNew ecuIdentifer) {
        ArrayList<EcuInfo> ecuInfos = m_ecuInfo.get(ecuIdentifer.addr);
        ArrayList<EcuInfo> keptEcus = new ArrayList<>();
        for (EcuInfo ecuInfo : ecuInfos) {
            for (EcuIdent ecuIdent : ecuInfo.ecuIdents) {
                if (ecuIdent.supplier_code.equals(ecuIdentifer.supplier) &&
                        ecuIdent.version.equals(ecuIdentifer.version)) {
                    ecuInfo.exact_match = false;
                    keptEcus.add(ecuInfo);
                    if (ecuIdent.soft_version.equals(ecuIdentifer.soft_version)) {
                        ecuInfo.exact_match = true;
                        keptEcus.clear();
                        keptEcus.add(ecuInfo);
                        return keptEcus;
                    }
                }
            }
        }
        return keptEcus;
    }

    public ArrayList<String> getEcuByFunctionsAndType(String type) {
        Set<String> list = new HashSet<>();
        Iterator<ArrayList<EcuInfo>> ecuArrayIterator = m_ecuInfo.values().iterator();

        while (ecuArrayIterator.hasNext()) {
            ArrayList<EcuInfo> ecuArray = ecuArrayIterator.next();
            for (EcuInfo ecuInfo : ecuArray) {
                if ((ecuInfo.projects.contains(type) || type.isEmpty())
                        && m_ecuAddressing.containsKey(ecuInfo.addressId)) {
                    list.add(m_ecuAddressing.get(ecuInfo.addressId));
                }
            }
        }
        ArrayList<String> ret = new ArrayList<>();
        for (String txt : list) {
            ret.add(txt);
        }
        return ret;
    }

    public int getAddressByFunction(String name) {
        Set<Integer> keySet = m_ecuAddressing.keySet();
        for (Integer i : keySet) {
            if (m_ecuAddressing.get(i) == name) {
                return i;
            }
        }
        return -1;
    }

    public String[] getProjects() {
        return m_projectSet.toArray(new String[m_projectSet.size()]);
    }

    public String[] getModels() {
        return MODELSMAP.values().toArray(new String[MODELSMAP.size()]);
    }

    public String getProjectFromModel(String model) {
        Iterator it = MODELSMAP.entrySet().iterator();
        String result = "";
        if (!model.toUpperCase().equals("ALL")) {
            while (it.hasNext()) {
                Map.Entry keyval = (Map.Entry) it.next();
                if (((String) keyval.getValue()).toUpperCase().equals(model.toUpperCase())) {
                    result = (String) keyval.getKey();
                    break;
                }
            }
        }
        buildMaps(result);
        return result;
    }

    public void buildMaps(String code) {
        if (Projects == null) {
            throw new RuntimeException("projects.json not found or not loaded!");
        }
        if (code.isEmpty()) {
            code = "ALL";
        }
        m_ecuAddressing.clear();
        // dnat
        TXADDRMAP.clear();
        // dnat_ext
        TXADDRMAP_EXT.clear();
        // snat
        RXADDRMAP.clear();
        // snat_ext
        RXADDRMAP_EXT.clear();

        for (Map.Entry<String, ProjectData.Project> p : Projects.projects.entrySet()) {
            if (Objects.equals(p.getValue().code, code)) {
                current_project_code = code.toUpperCase();
                current_project_name = p.getKey();
                for (Map.Entry<String, String[]> a : p.getValue().addressing.entrySet()) {
                    Integer add_key = Integer.parseInt(a.getKey().trim(), 16);
                    String add_name = a.getValue()[1].trim();
                    m_ecuAddressing.put(add_key, add_name);
                }
                // dnat
                for (Map.Entry<String, String> d : p.getValue().dnat.entrySet()) {
                    Integer dnat_key = Integer.parseInt(d.getKey().trim(), 16);
                    String dnat_name = d.getValue().trim();
                    TXADDRMAP.put(dnat_key, dnat_name);
                }
                // dnat_ext
                for (Map.Entry<String, String> d : p.getValue().dnat_ext.entrySet()) {
                    Integer dnat_ext_key = Integer.parseInt(d.getKey().trim(), 16);
                    String dnat_ext_name = d.getValue().trim();
                    TXADDRMAP_EXT.put(dnat_ext_key, dnat_ext_name);
                }
                // snat
                for (Map.Entry<String, String> s : p.getValue().snat.entrySet()) {
                    Integer snat_key = Integer.parseInt(s.getKey().trim(), 16);
                    String snat_name = s.getValue().trim();
                    RXADDRMAP.put(snat_key, snat_name);
                }
                // snat_ext
                for (Map.Entry<String, String> s : p.getValue().snat_ext.entrySet()) {
                    Integer snat_ext_key = Integer.parseInt(s.getKey().trim(), 16);
                    String snat_ext_name = s.getValue().trim();
                    RXADDRMAP.put(snat_ext_key, snat_ext_name);
                }
            }
        }
    }

    private void loadModels() {
        for (Map.Entry<String, ProjectData.Project> p : Projects.projects.entrySet()) {
            MODELSMAP.put(p.getValue().code, p.getKey());
        }
    }

    private void filterProjects() {
        Iterator<String> its = m_projectSet.iterator();
        while (its.hasNext()) {
            Set<String> modelKeySet = MODELSMAP.keySet();
            String project = its.next();
            if (!MODELSMAP.containsKey(project)) {
                MODELSMAP.remove(project);
            }
        }
    }

    public void checkMissings() {
        Iterator<String> its = m_projectSet.iterator();
        while (its.hasNext()) {
            Set<String> modelKeySet = MODELSMAP.keySet();
            String project = its.next();
            if (!MODELSMAP.containsKey(project)) {
                System.out.println("?? Missing " + project);
            }
        }
    }

    private String getResourceAsString(String resource_name) {
        InputStream ecu_stream = getClass().getClassLoader().getResourceAsStream(resource_name);
        String line;
        BufferedReader br;
        StringBuilder sb = new StringBuilder();

        try {
            br = new BufferedReader(new InputStreamReader(ecu_stream));
            while ((line = br.readLine()) != null) {
                sb.append(line + "\n");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sb.toString().trim();
    }

    private void loadProjectsData() {
        try {
            // Name of the JSON file
            String addressingResource = "projects.json";

            // Read the JSON file as a String
            String projectsJson = getResourceAsString(addressingResource);

            // Convert the JSON string to a JSONObject
            JSONObject jsonObject = new JSONObject(projectsJson);

            // Extract the 'projects' object from the JSON
            JSONObject projectsJsonObj = jsonObject.getJSONObject("projects");

            // Create a new Projects object
            ProjectData.Projects projects = new ProjectData.Projects();

            // Iterate through the project keys (e.g., "All")
            Iterator<String> keys = projectsJsonObj.keys(); // Get an iterator of the keys
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject projectJson = projectsJsonObj.getJSONObject(key);
                ProjectData.Project project = new ProjectData.Project();

                // Load project information
                project.code = projectJson.getString("code");

                // Load the addressing data
                JSONObject addressingJson = projectJson.getJSONObject("addressing");
                Iterator<String> addressingKeys = addressingJson.keys();
                while (addressingKeys.hasNext()) {
                    String addressingKey = addressingKeys.next();
                    JSONArray addressArray = addressingJson.getJSONArray(addressingKey);
                    String[] addresses = new String[addressArray.length()];
                    for (int j = 0; j < addressArray.length(); j++) {
                        addresses[j] = addressArray.getString(j);
                    }
                    project.addressing.put(addressingKey, addresses);
                }

                // Load snat data
                JSONObject snatJson = projectJson.getJSONObject("snat");
                Iterator<String> snatKeys = snatJson.keys();
                while (snatKeys.hasNext()) {
                    String snatKey = snatKeys.next();
                    project.snat.put(snatKey, snatJson.getString(snatKey));
                }

                // Load snat_ext data
                JSONObject snatExtJson = projectJson.getJSONObject("snat_ext");
                Iterator<String> snatExtKeys = snatExtJson.keys();
                while (snatExtKeys.hasNext()) {
                    String snatExtKey = snatExtKeys.next();
                    project.snat_ext.put(snatExtKey, snatExtJson.getString(snatExtKey));
                }

                // Load dnat data
                JSONObject dnatJson = projectJson.getJSONObject("dnat");
                Iterator<String> dnatKeys = dnatJson.keys();
                while (dnatKeys.hasNext()) {
                    String dnatKey = dnatKeys.next();
                    project.dnat.put(dnatKey, dnatJson.getString(dnatKey));
                }

                // Load dnat_ext data
                JSONObject dnatExtJson = projectJson.getJSONObject("dnat_ext");
                Iterator<String> dnatExtKeys = dnatExtJson.keys();
                while (dnatExtKeys.hasNext()) {
                    String dnatExtKey = dnatExtKeys.next();
                    project.dnat_ext.put(dnatExtKey, dnatExtJson.getString(dnatExtKey));
                }

                // Add the project to the projects map
                projects.projects.put(key, project);
            }

            // Save the projects in the global variable
            Projects = projects;
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public String loadDatabase(String ecuFilename, String appDir) throws DatabaseException {
        if (m_loaded) {
            Log.e("EcuDatabase", "Database already loaded");
            return m_ecuFilePath;
        }
        File checkEcuFile = new File(ecuFilename);
        if (!checkEcuFile.exists())
            ecuFilename = "";

        if (ecuFilename.isEmpty()) {
            ecuFilename = searchEcuFile(new File(Environment.getExternalStorageDirectory().getPath()));
        }
        if (ecuFilename.isEmpty()) {
            ecuFilename = searchEcuFile(new File(Environment.getDataDirectory().getPath()));
        }
        if (ecuFilename.isEmpty()) {
            ecuFilename = searchEcuFile(new File("/storage"));
        }
        if (ecuFilename.isEmpty()) {
            ecuFilename = searchEcuFile(new File("/mnt"));
        }
        if (ecuFilename.isEmpty()) {
            throw new DatabaseException("Ecu file (ecu.zip) not found");
        }

        String bytes;
        m_ecuFilePath = ecuFilename;
        String indexFileName = appDir + "/ecu.idx";

        File indexFile = new File(indexFileName);
        File ecuFile = new File(m_ecuFilePath);
        if (!ecuFile.exists()) {
            throw new DatabaseException("Archive (ecu.zip) file not found");
        }
        long indexTimeStamp = indexFile.lastModified();
        long ecuTimeStamp = ecuFile.lastModified();
        m_zipFileSystem = new ZipFileSystem(m_ecuFilePath, appDir);

        /*
         * If index is already made, use it
         * Also check files exists and timestamps to force [re]scan
         */
        if (indexFile.exists() && (indexTimeStamp > ecuTimeStamp) && m_zipFileSystem.importZipEntries()) {
            bytes = m_zipFileSystem.getZipFile("db.json");
            if (bytes.isEmpty()) {
                throw new DatabaseException("Database (db.json) file not found");
            }
        } else {
            /*
             * Else create it
             */
            m_zipFileSystem.getZipEntries();
            m_zipFileSystem.exportZipEntries();
            bytes = m_zipFileSystem.getZipFile("db.json");
            if (bytes.isEmpty()) {
                throw new DatabaseException("Database (db.json) file not found");
            }
        }

        JSONObject jsonRootObject;
        try {
            jsonRootObject = new JSONObject(bytes);
        } catch (Exception e) {
            throw new DatabaseException("JSON conversion issue");
        }

        m_projectSet = new HashSet<>();
        Set<Integer> addressSet = new HashSet<>();
        Iterator<String> keys = jsonRootObject.keys();
        for (; keys.hasNext(); ) {
            String href = keys.next();
            try {
                JSONObject ecuJsonObject = jsonRootObject.getJSONObject(href);
                JSONArray projectJsonObject = ecuJsonObject.getJSONArray("projects");
                Set<String> projectsSet = new HashSet<>();
                for (int i = 0; i < projectJsonObject.length(); ++i) {
                    String project = projectJsonObject.getString(i);
                    String upperCaseProject = project.toUpperCase();
                    projectsSet.add(upperCaseProject);
                    m_projectSet.add(upperCaseProject);
                }
                int addrId = Integer.parseInt(ecuJsonObject.getString("address"), 16);
                addressSet.add(addrId);
                EcuInfo info = new EcuInfo();
                info.ecuName = ecuJsonObject.getString("ecuname");
                info.href = href;
                info.projects = projectsSet;
                info.addressId = addrId;
                info.protocol = ecuJsonObject.getString("protocol");
                JSONArray jsAutoIdents = ecuJsonObject.getJSONArray("autoidents");
                info.ecuIdents = new EcuIdent[jsAutoIdents.length()];
                for (int i = 0; i < jsAutoIdents.length(); ++i) {
                    JSONObject jsAutoIdent = jsAutoIdents.getJSONObject(i);
                    info.ecuIdents[i] = new EcuIdent();
                    info.ecuIdents[i].soft_version = jsAutoIdent.getString("soft_version");
                    info.ecuIdents[i].supplier_code = jsAutoIdent.getString("supplier_code");
                    info.ecuIdents[i].version = jsAutoIdent.getString("version");
                    info.ecuIdents[i].diagnostic_version = jsAutoIdent.getString("diagnostic_version");
                }
                ArrayList<EcuInfo> ecuList;
                if (!m_ecuInfo.containsKey(addrId)) {
                    ecuList = new ArrayList<>();
                    m_ecuInfo.put(addrId, ecuList);
                } else {
                    ecuList = m_ecuInfo.get(addrId);
                }
                ecuList.add(info);
            } catch (Exception e) {
                e.printStackTrace();
                throw new DatabaseException("JSON parsing issue");
            }
        }

        Set<Integer> keySet = new HashSet<>(m_ecuAddressing.keySet());
        for (Integer key : keySet) {
            if (!addressSet.contains(key)) {
                m_ecuAddressing.remove(key);
            }
        }

        m_loaded = true;
        filterProjects();
        return ecuFilename;
    }

    public boolean isLoaded() {
        return m_loaded;
    }

    public String searchEcuFile(File dir) {
        if (!dir.exists()) {
            return "";
        }
        File listFile[] = dir.listFiles();
        if (listFile != null) {
            for (File f : listFile) {
                if (f.isDirectory()) {
                    String res = searchEcuFile(f);
                    if (!res.isEmpty())
                        return res;
                } else {
                    if (f.getName().equalsIgnoreCase("ecu.zip")) {
                        return f.getAbsolutePath();
                    }
                }
            }
        }
        return "";
    }

    public String getZipFile(String filePath) {
        return m_zipFileSystem.getZipFile(filePath);
    }

    public String getRxAddressById(int id) {
        return RXADDRMAP.get(id);
    }

    public String getTxAddressById(int id) {
        return TXADDRMAP.get(id);
    }

    public String getRxExtAddressById(int id) {
        return RXADDRMAP_EXT.get(id);
    }

    public String getTxExtAddressById(int id) {
        return TXADDRMAP_EXT.get(id);
    }

    public ZipFileSystem getZipFileSystem() {
        return m_zipFileSystem;
    }

    public class EcuIdent {
        public String supplier_code, soft_version, version, diagnostic_version;
    }

    public class EcuInfo {
        public Set<String> projects;
        public String href;
        public String ecuName, protocol;
        public int addressId;
        public EcuIdent ecuIdents[];
        public boolean exact_match;
    }

    public class DatabaseException extends Exception {
        public DatabaseException(String message) {
            super(message);
        }
    }

    public class EcuIdentifierNew {
        public String supplier, version, soft_version, diag_version;
        public int addr;

        public EcuIdentifierNew() {
            reInit(-1);
        }

        public void reInit(int addr) {
            this.addr = addr;
            supplier = version = soft_version = diag_version = "";
        }

        public boolean isFullyFilled() {
            return !supplier.isEmpty() && !version.isEmpty() && !soft_version.isEmpty() && !diag_version.isEmpty();
        }
    }
}
