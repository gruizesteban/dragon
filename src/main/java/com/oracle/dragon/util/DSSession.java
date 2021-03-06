package com.oracle.dragon.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.database.DatabaseClient;
import com.oracle.bmc.database.DatabaseWaiters;
import com.oracle.bmc.database.model.*;
import com.oracle.bmc.database.requests.*;
import com.oracle.bmc.database.responses.*;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.GetUserRequest;
import com.oracle.bmc.identity.responses.GetUserResponse;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.BucketSummary;
import com.oracle.bmc.objectstorage.model.CreateBucketDetails;
import com.oracle.bmc.objectstorage.requests.CreateBucketRequest;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;
import com.oracle.bmc.objectstorage.requests.ListBucketsRequest;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import com.oracle.bmc.objectstorage.responses.CreateBucketResponse;
import com.oracle.bmc.objectstorage.responses.GetNamespaceResponse;
import com.oracle.bmc.objectstorage.responses.ListBucketsResponse;
import com.oracle.bmc.objectstorage.transfer.UploadConfiguration;
import com.oracle.bmc.objectstorage.transfer.UploadManager;
import com.oracle.bmc.workrequests.WorkRequestClient;
import com.oracle.bmc.workrequests.model.WorkRequestError;
import com.oracle.bmc.workrequests.requests.GetWorkRequestRequest;
import com.oracle.bmc.workrequests.requests.ListWorkRequestErrorsRequest;
import com.oracle.bmc.workrequests.responses.GetWorkRequestResponse;
import com.oracle.bmc.workrequests.responses.ListWorkRequestErrorsResponse;
import com.oracle.dragon.model.LocalDragonConfiguration;
import com.oracle.dragon.stacks.CodeGenerator;
import com.oracle.dragon.stacks.StackType;
import com.oracle.dragon.util.exception.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.oracle.dragon.util.Console.*;

/**
 * DRAGON Stack session.
 */
public class DSSession {

    /**
     * Current version.
     */
    public static final String VERSION = "2.0.1";

    public static final String CONFIGURATION_FILENAME = "dragon.config";
    public static final String LOCAL_CONFIGURATION_FILENAME = "local_dragon.config.json";

    private static final int OCI_ALWAYS_FREE_DATABASE_NUMBER_LIMIT = 2;
    private static final String CONFIG_REGION = "region";
    private static final String CONFIG_FINGERPRINT = "fingerprint";
    private static final String CONFIG_DATABASE_TYPE = "database_type";
    private static final String CONFIG_DATABASE_USER_NAME = "database_user_name";
    private static final String CONFIG_DATABASE_PASSWORD = "database_password";
    private static final String CONFIG_DATABASE_LICENSE_TYPE = "database_license_type";
    private static final String CONFIG_COLLECTIONS = "database_collections";
    private static final String CONFIG_COMPARTMENT_ID = "compartment_id";
    private static final String CONFIG_TENANCY_ID = "tenancy";
    public static final String CONFIG_KEY_FILE = "key_file";
    private static final String CONFIG_USER = "user";
    private static final String CONFIG_AUTH_TOKEN = "auth_token";
    private static final String CONFIG_DATA_PATH = "data_path";

    // Code generation
    private boolean createStack;
    private StackType stackType;
    private String stackName = "frontend";

    private LocalDragonConfiguration localConfiguration;

    public enum Platform {
        Windows,
        Linux,
        MacOS,
        Unsupported
    }

    public enum Operation {
        CreateDatabase,
        DestroyDatabase,
        LoadData
    }

    public enum Section {
        CommandLineParameters("Command line parameters"),
        OCIConfiguration("Oracle Cloud Infrastructure configuration"),
        OCIConnection("OCI API endpoints"),
        DatabaseTermination("Database termination"),
        DatabaseCreation("Database creation"),
        DatabaseWalletDownload("Database wallet download"),
        DatabaseConfiguration("Database configuration"),
        ObjectStorageConfiguration("Object storage configuration"),
        LoadDataIntoCollections("Data loading"),
        LocalConfiguration("Local configuration"),
        CreateStack("Stack creation");

        private final String name;


        Section(String name) {
            this.name = name;
        }

        public void printlnKO() {
            System.out.print(Style.ANSI_RED);
            printBoundedln(name, "ko");
        }

        public void printlnOK() {
            System.out.print(Style.ANSI_GREEN);
            printBoundedln(name, "ok");
        }

        public void print(String msg) {
            //System.out.print(Style.ANSI_BLUE_BACKGROUND);
            printBounded(name, msg);
        }

        public void printlnKO(String msg) {
            System.out.print(Style.ANSI_RED);
            printBoundedln(name, String.format("ko [%s]", msg));
        }

        public void printlnOK(String msg) {
            System.out.print(Style.ANSI_GREEN);
            printBoundedln(name, String.format("ok [%s]", msg));
        }
    }

    public static final Platform platform;
    public static final boolean OCICloudShell;

    private Section section;
    private ConfigFileReader.ConfigFile configFile;
    private AuthenticationDetailsProvider provider;
    private DatabaseClient dbClient;
    private WorkRequestClient workRequestClient;
    private ObjectStorageClient objectStorageClient;
    private IdentityClient identityClient;

    private String databaseUserName = "dragon";

    /**
     * The database name to create.
     */
    private String dbName = "DRAGON";

    private String profileName = "DEFAULT";

    /**
     * The OCI region to manage.
     */
    private String region = "";

    private Operation operation = Operation.CreateDatabase;

    enum DatabaseType {
        AlwaysFreeATP,
        AJD,
        ATP,
        ADW
    }

    private DatabaseType databaseType = DatabaseType.AlwaysFreeATP;

    enum LicenseType {
        LicenseIncluded,
        BYOL
    }

    private LicenseType licenseType = LicenseType.LicenseIncluded;

    /**
     * Load data into collections.
     */
    private boolean load = false;

    /**
     * Display information about region, compartment, user...
     */
    private boolean info = false;

    private File dataPath = new File(".");

    static {
        final String osName = System.getProperty("os.name").toLowerCase();
        if (osName.startsWith("windows")) {
            platform = Platform.Windows;
            Console.ENABLE_COLORS = false;
            OCICloudShell = false;
        } else if (osName.startsWith("linux")) {
            platform = Platform.Linux;
            System.setProperty("java.awt.headless", "true");

            if (System.getenv("CLOUD_SHELL_TOOL_SET") != null && System.getenv("OCI_REGION") != null && System.getenv("OCI_TENANCY") != null) {
                OCICloudShell = true;
            } else {
                OCICloudShell = false;
            }
        } else if (osName.startsWith("mac os")) {
            platform = Platform.MacOS;
            System.setProperty("java.awt.headless", "true");
            OCICloudShell = false;
        } else {
            platform = Platform.Unsupported;
            OCICloudShell = false;
        }
    }

    private static void banner() {
        print(String.format("%sDRAGON Stack manager v%s", Style.ANSI_YELLOW, VERSION));
        println();
        println();
    }

    public DSSession() throws UnsupportedPlatformException {
        banner();

        if (platform == Platform.Unsupported) {
            throw new UnsupportedPlatformException(System.getProperty("os.name"));
        }
    }

    public void analyzeCommandLineParameters(String[] args) throws MissingDatabaseNameParameterException, MissingProfileNameParameterException {
        section = Section.CommandLineParameters;
        section.print("analyzing");
        for (int i = 0; i < args.length; i++) {
            final String arg = args[i].toLowerCase();
            switch (arg) {
                case "-db":
                    if (i + 1 < args.length) {
                        dbName = args[++i].toUpperCase();
                    } else {
                        section.printlnKO();
                        throw new MissingDatabaseNameParameterException();
                    }
                    break;

                case "-p":
                case "-profile":
                case "--profile":
                    if (i + 1 < args.length) {
                        profileName = args[++i].toUpperCase();
                    } else {
                        section.printlnKO();
                        throw new MissingProfileNameParameterException();
                    }
                    break;

                case "-destroy":
                case "--destroy":
                    operation = Operation.DestroyDatabase;
                    break;

                case "-load":
                case "--load":
                    load = true;
                    if(localConfiguration != null) {
                        operation = Operation.LoadData;
                    }
                    break;

                case "-info":
                case "--info":
                    info = true;
                    break;

                case "-config-template":
                case "--config-template":
                    section.printlnOK();
                    printlnConfigurationTemplate();
                    System.exit(0);
                    break;

                case "-create-react-app":
                case "--create-react-app":
                    createStack = true;
                    stackType = StackType.REACT;
                    if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        i++;
                        stackName = args[i];
                    }
                    break;

                case "-h":
                case "-?":
                case "/?":
                case "/h":
                case "-help":
                case "--help":
                    section.printlnOK();
                    displayUsage();
                    System.exit(0);
                    break;

                default:
                    section.printlnKO("bad parameter: " + arg);
                    displayUsage();
                    System.exit(-10000);
            }
        }
        section.printlnOK();
    }

    private void displayUsage() {
        println("Usage:");
        println("  -config-template        \tdisplays a configuration file template");
        println("  -profile <profile name> \tto choose the given profile name from " + CONFIGURATION_FILENAME + " (instead of DEFAULT)");
        println("  -db <database name>     \tto denote the database name to create");
        println("  -load                   \tloads corresponding data into collections");
        println("  -create-react-app [name]\tcreates a React frontend (instead of frontend)");
        println("  -destroy                \tto destroy the database");
    }

    public static void printlnConfigurationTemplate() {
        println("Configuration template (save the content in a file named \"" + CONFIGURATION_FILENAME + "\"):");
        println();
        println();
        println(" # DEFAULT profile (case sensitive), you can define others: ASHBURN_REGION or TEST_ENVIRONMENT");
        println(" # You can choose a profile using the -profile command line argument");
        println("[DEFAULT]");
        println();
        println(" # OCID of the user connecting to Oracle Cloud Infrastructure APIs. To get the value, see:");
        println(" # https://docs.cloud.oracle.com/en-us/iaas/Content/API/Concepts/apisigningkey.htm#five");
        println("user=ocid1.user.oc1..<unique_ID>");
        println();
        println(" # Full path and filename of the SSH private key (use *solely* forward slashes).");
        println(" # /!\\ Warning: The key pair must be in PEM format. For instructions on generating a key pair in PEM format, see:");
        println(" # https://docs.cloud.oracle.com/en-us/iaas/Content/API/Concepts/apisigningkey.htm#Required_Keys_and_OCIDs");
        println("key_file=<full path to SSH private key file>");
        println();
        println(" # Fingerprint for the SSH *public* key that was added to the user mentioned above. To get the value, see:");
        println(" # https://docs.cloud.oracle.com/en-us/iaas/Content/API/Concepts/apisigningkey.htm#four");
        println("fingerprint=<fingerprint associated with the corresponding SSH *public* key>");
        println();
        println(" # OCID of your tenancy. To get the value, see:");
        println(" # https://docs.cloud.oracle.com/en-us/iaas/Content/API/Concepts/apisigningkey.htm#five");
        println("tenancy=ocid1.tenancy.oc1..<unique_ID>");
        println();
        println(" # An Oracle Cloud Infrastructure region identifier. For a list of possible region identifiers, check here:");
        println(" # https://docs.cloud.oracle.com/en-us/iaas/Content/General/Concepts/regions.htm#top");
        println("region=eu-frankfurt-1");
        println();
        println(" # OCID of the compartment to use for resources creation. to get more information about compartments, see:");
        println(" # https://docs.cloud.oracle.com/en-us/iaas/Content/Identity/Tasks/managingcompartments.htm?Highlight=compartment%20ocid#Managing_Compartments");
        println("compartment_id=ocid1.compartment.oc1..<unique_ID>");
        println();
        println(" # Authentication token that will be used for OCI Object Storage configuration, see:");
        println(" # https://docs.cloud.oracle.com/en-us/iaas/Content/Registry/Tasks/registrygettingauthtoken.htm?Highlight=user%20auth%20tokens");
        println("auth_token=<authentication token>");
        println();
        println(" # Autonomous Database Type: ajd (for Autonomous JSON Database), atp (for Autonomous Transaction Processing), adw (for Autonomous Data Warehouse)");
        println(" # Empty value means Always Free Autonomous Transaction Processing.");
        println("# database_type=");
        println();
        println(" # Uncomment to specify another database user name than dragon (default)");
        println("# database_user_name=<your database user name>");
        println();
        println(" # The database password used for database creation and dragon user");
        println(" # - 12 chars minimum and 30 chars maximum");
        println(" # - can't contain the \"dragon\" word");
        println(" # - contains 1 digit minimum");
        println(" # - contains 1 lower case char");
        println(" # - contains 1 upper case char");
        println("database_password=<database password>");
        println();
        println(" # Uncomment to ask for Bring Your Own Licenses model (doesn't work for Always Free and AJD)");
        println("# database_license_type=byol");
        println();
        println(" # A list of coma separated JSON collection name(s) that you wish to get right after database creation");
        println("# database_collections=");
        println();
        println(" # Path to a folder where data to load into collections can be found (default to current directory)");
        println("data_path=.");
        println();
    }

    public void loadLocalConfiguration(boolean displaySection) throws DSException {
        final File localConfigurationFile = new File(LOCAL_CONFIGURATION_FILENAME);

        if (localConfigurationFile.exists() && localConfigurationFile.isFile()) {
            if (displaySection) {
                section = Section.LocalConfiguration;
                section.print("parsing");
            }

            final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            try {
                localConfiguration = mapper.readValue(localConfigurationFile, LocalDragonConfiguration.class);
            } catch (IOException e) {
                throw new LoadLocalConfigurationException(LOCAL_CONFIGURATION_FILENAME, e);
            }

            if(displaySection) {
                section.printlnOK();
            }
        }
    }

    public void loadConfiguration() throws DSException {
        section = Section.OCIConfiguration;
        section.print("parsing");

        try {
            configFile = ConfigFileReader.parse(CONFIGURATION_FILENAME, profileName);

            if ((region = configFile.get(CONFIG_REGION)) == null) {
                section.printlnKO();
                throw new ConfigurationMissesParameterException(CONFIG_REGION);
            }

            region = region.toUpperCase().replaceAll("-", "_");

            if (configFile.get(CONFIG_KEY_FILE) == null) {
                section.printlnKO();
                throw new ConfigurationMissesParameterException(CONFIG_KEY_FILE);
            }
            if (configFile.get(CONFIG_TENANCY_ID) == null) {
                section.printlnKO();
                throw new ConfigurationMissesParameterException(CONFIG_TENANCY_ID);
            }
            if (configFile.get(CONFIG_COMPARTMENT_ID) == null) {
                section.printlnKO();
                throw new ConfigurationMissesParameterException(CONFIG_COMPARTMENT_ID);
            }
            if (configFile.get(CONFIG_DATABASE_PASSWORD) == null) {
                section.printlnKO();
                throw new ConfigurationMissesParameterException(CONFIG_DATABASE_PASSWORD);
            }
            if (configFile.get(CONFIG_USER) == null) {
                section.printlnKO();
                throw new ConfigurationMissesParameterException(CONFIG_USER);
            }
            if (configFile.get(CONFIG_AUTH_TOKEN) == null) {
                section.printlnKO();
                throw new ConfigurationMissesParameterException(CONFIG_AUTH_TOKEN);
            }
            if (configFile.get(CONFIG_FINGERPRINT) == null) {
                section.printlnKO();
                throw new ConfigurationMissesParameterException(CONFIG_FINGERPRINT);
            } else {
                final String fingerprintValue = configFile.get(CONFIG_FINGERPRINT);
                if (fingerprintValue.length() != 47) {
                    throw new ConfigurationBadFingerprintParameterException(CONFIG_FINGERPRINT, CONFIGURATION_FILENAME, fingerprintValue);
                }
            }

            // Optional config file parameters
            if (configFile.get(CONFIG_DATABASE_USER_NAME) != null) {
                databaseUserName = configFile.get(CONFIG_DATABASE_USER_NAME);
            }

            if (configFile.get(CONFIG_DATABASE_LICENSE_TYPE) != null) {
                if (LicenseType.BYOL.toString().equalsIgnoreCase(configFile.get(CONFIG_DATABASE_LICENSE_TYPE))) {
                    licenseType = LicenseType.BYOL;
                } else {
                    throw new ConfigurationWrongDatabaseLicenseTypeException(configFile.get(CONFIG_DATABASE_LICENSE_TYPE));
                }
            } else {
                licenseType = LicenseType.LicenseIncluded;
            }

            if (configFile.get(CONFIG_DATABASE_TYPE) != null) {
                if (DatabaseType.AJD.toString().equalsIgnoreCase(configFile.get(CONFIG_DATABASE_TYPE))) {
                    databaseType = DatabaseType.AJD;
                } else if (DatabaseType.ATP.toString().equalsIgnoreCase(configFile.get(CONFIG_DATABASE_TYPE))) {
                    databaseType = DatabaseType.ATP;
                } else if (DatabaseType.ADW.toString().equalsIgnoreCase(configFile.get(CONFIG_DATABASE_TYPE))) {
                    databaseType = DatabaseType.ADW;
                } else {
                    throw new ConfigurationWrongDatabaseTypeException(configFile.get(CONFIG_DATABASE_TYPE));
                }
            }

            if (load) {
                if (configFile.get(CONFIG_DATA_PATH) != null) {
                    final File tempPath = new File(configFile.get(CONFIG_DATA_PATH));

                    if (!tempPath.exists()) {
                        throw new ConfigurationDataPathNotFoundException(configFile.get(CONFIG_DATA_PATH));
                    }

                    if (!tempPath.isDirectory()) {
                        throw new ConfigurationDataPathDirectoryException(configFile.get(CONFIG_DATA_PATH));
                    }

                    dataPath = tempPath;
                }
            }

        } catch (java.io.FileNotFoundException fnfe) {
            section.printlnKO();
            throw new ConfigurationFileNotFoundException();
        } catch (IOException ioe) {
            section.printlnKO();
            throw new ConfigurationLoadException(ioe);
        } catch (IllegalArgumentException iae) {
            if (iae.getMessage().startsWith("No profile named")) {
                section.printlnKO("profile " + profileName + " not found");
                throw new ConfigurationProfileNotFoundException(profileName);
            }

            throw new ConfigurationParsingException(iae);
        }

        section.printlnOK();
    }

    private void initializeClients() throws OCIAPIAuthenticationPrivateKeyNotFoundException, OCIAPIDatabaseException {
        section = Section.OCIConnection;
        section.print("authentication pending");
        provider = new ConfigFileAuthenticationDetailsProvider(configFile);

        section.print("database pending");
        try {
            dbClient = new DatabaseClient(provider);
            dbClient.setRegion(region);
        } catch (IllegalArgumentException iae) {
            if (iae.getMessage().startsWith("Could not find private key")) {
                section.printlnKO("private key not found");
                throw new OCIAPIAuthenticationPrivateKeyNotFoundException(configFile.get(CONFIG_KEY_FILE));
            }

            throw new OCIAPIDatabaseException(iae);
        }
    }

    public void work() throws DSException {
        switch (operation) {
            case CreateDatabase:
                if (localConfiguration == null) {
                    initializeClients();
                    createADB();
                }
                break;

            case DestroyDatabase:
                if (localConfiguration != null && localConfiguration.getDbName().equals(dbName)) {
                    initializeClients();
                    destroyDatabase();
                }
                break;

            case LoadData:
                if (localConfiguration != null && localConfiguration.getDbName().equals(dbName)) {
                    initializeClients();
                    loadData();
                }
                break;
        }

        if (operation == Operation.CreateDatabase && createStack) {
            final CodeGenerator c = new CodeGenerator(stackType, stackName, localConfiguration);
            c.work();
        }
    }

    private void createADB() throws DSException {
        section = Section.DatabaseCreation;
        section.print("checking existing databases");

        final ListAutonomousDatabasesRequest listADB = ListAutonomousDatabasesRequest.builder().compartmentId(configFile.get("compartment_id")).build();
        final ListAutonomousDatabasesResponse listADBResponse = dbClient.listAutonomousDatabases(listADB);
        final Set<String> existingFreeADB = new TreeSet<>();
        boolean dbNameAlreadyExists = false;

        for (AutonomousDatabaseSummary adb : listADBResponse.getItems()) {
            if (adb.getLifecycleState() != AutonomousDatabaseSummary.LifecycleState.Terminated) {
                if (adb.getDbName().equals(dbName)) {
                    dbNameAlreadyExists = true;
                }
                if (adb.getIsFreeTier()) {
                    existingFreeADB.add(adb.getDbName());
                }
            }
        }

        if (databaseType == DatabaseType.AlwaysFreeATP && existingFreeADB.size() == OCI_ALWAYS_FREE_DATABASE_NUMBER_LIMIT) {
            section.printlnKO("limit reached");
            throw new AlwaysFreeDatabaseLimitReachedException(OCI_ALWAYS_FREE_DATABASE_NUMBER_LIMIT);
        }

        if (dbNameAlreadyExists) {
            section.printlnKO("duplicate name");
            throw new DatabaseNameAlreadyExistsException(dbName);
        }

        section.print("pending");
        CreateAutonomousDatabaseDetails createFreeRequest = CreateAutonomousDatabaseDetails.builder()
                .cpuCoreCount(1)
                .dataStorageSizeInTBs(1)
                .displayName(dbName + " Database")
                .adminPassword(configFile.get(CONFIG_DATABASE_PASSWORD))
                .dbName(dbName)
                .compartmentId(configFile.get(CONFIG_COMPARTMENT_ID))
                .dbWorkload(databaseType == DatabaseType.AlwaysFreeATP || databaseType == DatabaseType.ATP ? CreateAutonomousDatabaseBase.DbWorkload.Oltp :
                        (databaseType == DatabaseType.AJD ? CreateAutonomousDatabaseBase.DbWorkload.Ajd : CreateAutonomousDatabaseBase.DbWorkload.Dw))
                .isAutoScalingEnabled(Boolean.FALSE)
                .licenseModel(databaseType == DatabaseType.AlwaysFreeATP || databaseType == DatabaseType.AJD ? CreateAutonomousDatabaseBase.LicenseModel.LicenseIncluded : (licenseType == LicenseType.LicenseIncluded ? CreateAutonomousDatabaseBase.LicenseModel.LicenseIncluded :
                        CreateAutonomousDatabaseBase.LicenseModel.BringYourOwnLicense))
                .isPreviewVersionWithServiceTermsAccepted(Boolean.FALSE)
                .isFreeTier(databaseType == DatabaseType.AlwaysFreeATP ? Boolean.TRUE : Boolean.FALSE)
                .build();

        String workRequestId = null;
        AutonomousDatabase autonomousDatabase = null;
        workRequestClient = new WorkRequestClient(provider);

        try {
            CreateAutonomousDatabaseResponse responseCreate = dbClient.createAutonomousDatabase(CreateAutonomousDatabaseRequest.builder().createAutonomousDatabaseDetails(createFreeRequest).build());
            autonomousDatabase = responseCreate.getAutonomousDatabase();
            workRequestId = responseCreate.getOpcWorkRequestId();
        } catch (BmcException e) {
            //e.printStackTrace();
            if (e.getStatusCode() == 400 && e.getServiceCode().equals("LimitExceeded")) {
                section.printlnKO("limit reached");
                if (e.getMessage().startsWith("Tenancy has reached maximum limit for Free Tier Autonomous Database")) {
                    throw new AlwaysFreeDatabaseLimitReachedException(OCI_ALWAYS_FREE_DATABASE_NUMBER_LIMIT);
                } else {
                    throw new AutonomousDatabaseLimitReachedException(e.getMessage());
                }
            } else if (e.getStatusCode() == 400 && e.getServiceCode().equals("InvalidParameter") &&
                    e.getMessage().contains(dbName) && e.getMessage().contains("already exists")) {
                section.printlnKO("duplicate name");
                throw new DatabaseNameAlreadyExistsException(dbName);
            }
        }

        if (autonomousDatabase == null) {
            section.printlnKO();
            throw new OCIDatabaseCreationCantProceedFurtherException();
        }

        GetWorkRequestRequest getWorkRequestRequest = GetWorkRequestRequest.builder().workRequestId(workRequestId).build();
        boolean exit = false;
        long startTime = System.currentTimeMillis();
        float pendingProgressMove = 0f;
        boolean probe = true;
        GetWorkRequestResponse getWorkRequestResponse = null;
        do {
            if (probe) getWorkRequestResponse = workRequestClient.getWorkRequest(getWorkRequestRequest);
            switch (getWorkRequestResponse.getWorkRequest().getStatus()) {
                case Succeeded:
                    section.printlnOK(getDurationSince(startTime));
                    exit = true;
                    break;
                case Failed:
                    section.printlnKO();

                    final ListWorkRequestErrorsResponse response = workRequestClient.listWorkRequestErrors(ListWorkRequestErrorsRequest.builder().workRequestId(workRequestId).opcRequestId(getWorkRequestResponse.getOpcRequestId()).build());
                    final StringBuilder errors = new StringBuilder();
                    int i = 0;
                    for (WorkRequestError e : response.getItems()) {
                        if (i > 0) errors.append("\n");
                        errors.append(e.getMessage());
                        i++;
                    }

                    throw new OCIDatabaseCreationFaileDException(dbName, errors.toString());
                case Accepted:
                    section.print(String.format("accepted [%s]", getDurationSince(startTime)));
                    break;
                case InProgress:
                    section.print(String.format("in progress %.0f%% [%s]", Math.min(getWorkRequestResponse.getWorkRequest().getPercentComplete() + pendingProgressMove, 90f), getDurationSince(startTime)));
                    pendingProgressMove += Math.random() * 1.5f;
                    break;
            }

            sleep(500L);
            probe = !probe;
        } while (!exit);

        DatabaseWaiters waiter = dbClient.getWaiters();
        try {
            GetAutonomousDatabaseResponse responseGet = waiter.forAutonomousDatabase(GetAutonomousDatabaseRequest.builder().autonomousDatabaseId(autonomousDatabase.getId()).build(),
                    new AutonomousDatabase.LifecycleState[]{AutonomousDatabase.LifecycleState.Available}).execute();
            autonomousDatabase = responseGet.getAutonomousDatabase();
        } catch (Exception e) {
            section.printlnKO();
            throw new OCIDatabaseWaitForTerminationFailedException(e);
        }

        // The free ATP should now be available!

        section = Section.DatabaseWalletDownload;
        section.print("pending");
        GenerateAutonomousDatabaseWalletDetails atpWalletDetails = GenerateAutonomousDatabaseWalletDetails.builder().password(configFile.get(CONFIG_DATABASE_PASSWORD)).generateType(GenerateAutonomousDatabaseWalletDetails.GenerateType.Single).build();
        GenerateAutonomousDatabaseWalletResponse atpWalletResponse =
                dbClient.generateAutonomousDatabaseWallet(
                        GenerateAutonomousDatabaseWalletRequest.builder()
                                .generateAutonomousDatabaseWalletDetails(atpWalletDetails)
                                .autonomousDatabaseId(autonomousDatabase.getId())
                                .build());
        section.print("saving");

        final String walletFileName = dbName.toLowerCase() + ".zip";
        final File walletFile = new File(walletFileName);
        try {
            Files.copy(atpWalletResponse.getInputStream(), walletFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ioe) {
            throw new DatabaseWalletSavingException(walletFile.getAbsolutePath());
        }

        if (!ZipUtil.isValid(walletFile)) {
            section.printlnKO(String.format("%s is corrupted", walletFileName));
            throw new DatabaseWalletCorruptedException(walletFile.getAbsolutePath());
        }

        section.printlnOK(walletFileName);

        final ADBRESTService rSQLS = new ADBRESTService(autonomousDatabase.getConnectionUrls().getSqlDevWebUrl(), databaseUserName.toUpperCase(), configFile.get(CONFIG_DATABASE_PASSWORD));

        // Save the local config file as early as possible in case of problems afterward so that one can destroy it
        section = Section.LocalConfiguration;
        try (PrintWriter out = new PrintWriter(new BufferedOutputStream(new FileOutputStream(LOCAL_CONFIGURATION_FILENAME)))) {
            out.println(getConfigurationAsJSON(autonomousDatabase, rSQLS, true));
        } catch (IOException e) {
            throw new LocalConfigurationNotSavedException(e);
        }
        section.printlnOK();

        section = Section.DatabaseConfiguration;

        section.print(String.format("creating %s user", databaseUserName));
        createSchema(autonomousDatabase);

        if (configFile.get(CONFIG_COLLECTIONS) != null) {
            createCollections(rSQLS, autonomousDatabase);
        }

        section.printlnOK();

        // download Oracle Instant Client? (https://www.oracle.com/database/technologies/instant-client/downloads.html)
        //
        // Not for Always Free Tiers
        // Create backup bucket
        // - configure backup bucket

        section = Section.ObjectStorageConfiguration;
        section.print("pending");
        objectStorageClient = new ObjectStorageClient(provider);
        objectStorageClient.setRegion(region);

        section.print("checking existing buckets");
        final GetNamespaceResponse namespaceResponse = objectStorageClient.getNamespace(GetNamespaceRequest.builder().build());
        final String namespaceName = namespaceResponse.getValue();

        final ListBucketsRequest.Builder listBucketsBuilder = ListBucketsRequest.builder().namespaceName(namespaceName).compartmentId(configFile.get(CONFIG_COMPARTMENT_ID));

        String nextToken = null;
        boolean backupBucketExist = false;
        boolean dragonBucketExist = false;
        final String backupBucketName = "backup_" + dbName.toLowerCase();
        final String dragonBucketName = "dragon";
        do {
            listBucketsBuilder.page(nextToken);
            ListBucketsResponse listBucketsResponse = objectStorageClient.listBuckets(listBucketsBuilder.build());
            for (BucketSummary bucket : listBucketsResponse.getItems()) {
                if (!backupBucketExist && backupBucketName.equals(bucket.getName())) backupBucketExist = true;
                if (!dragonBucketExist && dragonBucketName.equals(bucket.getName())) dragonBucketExist = true;
            }
            nextToken = listBucketsResponse.getOpcNextPage();
        } while (nextToken != null);

        if (!dragonBucketExist) {
            section.print("creating dragon bucket");
            createManualBucket(namespaceName, dragonBucketName, true);
        }

        identityClient = new IdentityClient(provider);
        GetUserResponse userResponse = identityClient.getUser(GetUserRequest.builder().userId(configFile.get(CONFIG_USER)).build());

        section.print("database setup");

        try {
            rSQLS.execute(String.format(
                    "BEGIN\n" +
                            "    DBMS_CLOUD.CREATE_CREDENTIAL(credential_name => 'DRAGON_CREDENTIAL_NAME', username => '%s', password => '%s');\n" +
                            "    COMMIT;\n" +
                            "END;\n" +
                            "/", userResponse.getUser().getEmail(), configFile.get(CONFIG_AUTH_TOKEN)));
        } catch (RuntimeException re) {
            section.printlnKO();
            throw new ObjectStorageConfigurationFailedException();
        }

        if (databaseType != DatabaseType.AlwaysFreeATP) {
            if (!backupBucketExist) {
                section.print("creating manual backup bucket");
                createManualBucket(namespaceName, backupBucketName, false);
            }

            section.print("database backup setup");

            final ADBRESTService adminRSQLS = new ADBRESTService(autonomousDatabase.getConnectionUrls().getSqlDevWebUrl(), "ADMIN", configFile.get(CONFIG_DATABASE_PASSWORD));

            try {
                adminRSQLS.execute(String.format(
                        "ALTER DATABASE PROPERTY SET default_bucket='https://swiftobjectstorage." + getRegionForURL() + ".oraclecloud.com/v1/" + namespaceName + "';\n" +
                                "BEGIN\n" +
                                "    DBMS_CLOUD.CREATE_CREDENTIAL(credential_name => 'BACKUP_CREDENTIAL_NAME', username => '%s', password => '%s');\n" +
                                "    COMMIT;\n" +
                                "END;\n" +
                                "/\n" +
                                "ALTER DATABASE PROPERTY SET default_credential='ADMIN.BACKUP_CREDENTIAL_NAME'", userResponse.getUser().getEmail(), configFile.get(CONFIG_AUTH_TOKEN)));
            } catch (RuntimeException re) {
                section.printlnKO();
                throw new ObjectStorageConfigurationFailedException();
            }
        }

        section.printlnOK();

        if (load) {
            section = Section.LoadDataIntoCollections;
            loadData(namespaceName, rSQLS);
            section.printlnOK();
        }

        // reload just saved JSON local configuration as POJO for further processing (create stack...)
        loadLocalConfiguration(false);

        Console.println("You can connect to your database using SQL Developer Web:");
        final String url = rSQLS.getUrlPrefix() + "sign-in/?username=" + databaseUserName.toUpperCase() + "&r=_sdw%2F";
        Console.println("- URL  : " + url);
        Console.println("- login: " + databaseUserName.toLowerCase());
    }

    private void loadData() throws DSException {
        section = Section.LoadDataIntoCollections;

        objectStorageClient = new ObjectStorageClient(provider);
        objectStorageClient.setRegion(region);

        section.print("checking existing buckets");
        final GetNamespaceResponse namespaceResponse = objectStorageClient.getNamespace(GetNamespaceRequest.builder().build());
        final String namespaceName = namespaceResponse.getValue();

        final ADBRESTService rSQLS = new ADBRESTService(localConfiguration.getSqlDevWeb(), databaseUserName.toUpperCase(), configFile.get(CONFIG_DATABASE_PASSWORD));

        loadData(namespaceName, rSQLS);

        section.printlnOK();
    }

    private void loadData(final String namespaceName, final ADBRESTService rSQLS) throws DSException {
        loadCollections(namespaceName, rSQLS);
    }

    private void createSchema(AutonomousDatabase adb) throws DatabaseUserCreationFailedException {
        final ADBRESTService rSQLS = new ADBRESTService(adb.getConnectionUrls().getSqlDevWebUrl(), "ADMIN", configFile.get(CONFIG_DATABASE_PASSWORD));

        try {
            rSQLS.execute(String.format("create user %s identified by \"%s\" DEFAULT TABLESPACE DATA TEMPORARY TABLESPACE TEMP;\n" +
                    "alter user %s quota unlimited on data;\n" +
                    "grant dwrole, create session, soda_app, alter session to %s;\n" +
                    "grant execute on CTX_DDL to %s;\n" +
                    "grant select on v$mystat to %s;" +
                    "BEGIN\n" +
                    "    ords_admin.enable_schema(p_enabled => TRUE, p_schema => '%s', p_url_mapping_type => 'BASE_PATH', p_url_mapping_pattern => '%s', p_auto_rest_auth => TRUE);\n" +
                    "END;\n" +
                    "/", databaseUserName, configFile.get(CONFIG_DATABASE_PASSWORD), databaseUserName, databaseUserName, databaseUserName, databaseUserName, databaseUserName.toUpperCase(), databaseUserName.toLowerCase()));
        } catch (RuntimeException re) {
            section.printlnKO();
            throw new DatabaseUserCreationFailedException(re);
        }
    }

    private String getRegionForURL() {
        return region.replaceAll("_", "-").toLowerCase();
    }

    private void createManualBucket(String namespaceName, String bucketName, boolean events) throws ObjectStorageBucketCreationFailedException {
        CreateBucketRequest request = CreateBucketRequest.builder().namespaceName(namespaceName).createBucketDetails(
                CreateBucketDetails.builder().compartmentId(configFile.get(CONFIG_COMPARTMENT_ID)).name(bucketName).objectEventsEnabled(events).build()
        ).build();

        CreateBucketResponse response = objectStorageClient.createBucket(request);

        if (response.getBucket() == null || !response.getBucket().getName().equals(bucketName)) {
            section.printlnKO();
            throw new ObjectStorageBucketCreationFailedException(bucketName);
        }
    }

    private void createCollections(ADBRESTService rSQLS, AutonomousDatabase adb) {
        section.print("creating dragon collections");
        rSQLS.createSODACollection("dragon");
        section.print("storing dragon information");
        rSQLS.insertDocument("dragon", getConfigurationAsJSON(adb, rSQLS));

        for (String collectionName : configFile.get(CONFIG_COLLECTIONS).split(",")) {
            if (!"dragon".equals(collectionName)) {
                section.print("creating collection " + collectionName);
                rSQLS.createSODACollection(collectionName);
            }
        }
    }

    private String getConfigurationAsJSON(AutonomousDatabase adb, ADBRESTService rSQLS) {
        return getConfigurationAsJSON(adb, rSQLS, false);
    }

    private String getConfigurationAsJSON(AutonomousDatabase adb, ADBRESTService rSQLS, boolean local) {
        return String.format("{\"databaseServiceURL\": \"%s\", " +
                        "\"sqlDevWebAdmin\": \"%s\", " +
                        "\"sqlDevWeb\": \"%s\", " +
                        "\"apexURL\": \"%s\", " +
                        "\"omlURL\": \"%s\", " +
                        "\"sqlAPI\": \"%s\", " +
                        "\"sodaAPI\": \"%s\", " +
                        "\"version\": \"%s\"" +
                        (local ? ", \"dbName\": \"%s\", \"dbUserName\": \"%s\", \"dbUserPassword\": \"%s\""
                                : "") +
                        "}",
                adb.getServiceConsoleUrl(),
                adb.getConnectionUrls().getSqlDevWebUrl(),
                adb.getConnectionUrls().getSqlDevWebUrl().replaceAll("admin", databaseUserName.toLowerCase()),
                adb.getConnectionUrls().getApexUrl(),
                adb.getConnectionUrls().getMachineLearningUserManagementUrl(),
                rSQLS.getUrlSQLService(),
                rSQLS.getUrlSODAService(),
                adb.getDbVersion(),
                dbName, databaseUserName, configFile.get(CONFIG_DATABASE_PASSWORD)
        );
    }

    private void loadCollections(String namespaceName, ADBRESTService rSQLS) throws DSException {
        UploadConfiguration uploadConfiguration =
                UploadConfiguration.builder()
                        .allowMultipartUploads(true)
                        .allowParallelUploads(true)
                        .build();

        UploadManager uploadManager = new UploadManager(objectStorageClient, uploadConfiguration);


        for (String collectionName : configFile.get(CONFIG_COLLECTIONS).split(",")) {
            if (!"dragon".equals(collectionName)) {
                section.print("collection " + collectionName);

                // find all names starting by <collection name>_XXX.json and stored in some data folder (specified in CONFIGURATION_FILENAME)
                final File[] dataFiles = dataPath.listFiles(new JSONCollectionFilenameFilter(collectionName));

                if( dataFiles == null || dataFiles.length == 0 ) continue;

                Map<String, String> metadata = null;

                // upload them in parallel to OCI Object Storage
                int nb = 1;
                for (File file : dataFiles) {
                    section.print(String.format("collection %s: uploading file %d/%d", collectionName, nb, dataFiles.length));

                    PutObjectRequest request =
                            PutObjectRequest.builder()
                                    .bucketName("dragon")
                                    .namespaceName(namespaceName)
                                    .objectName(dbName + "/" + collectionName + "/" + file.getName())
                                    .contentType("application/json")
                                    //.contentLanguage(contentLanguage)
                                    //.contentEncoding("UTF-8")
                                    //.opcMeta(metadata)
                                    .build();

                    UploadManager.UploadRequest uploadDetails = UploadManager.UploadRequest.builder(file).allowOverwrite(true).build(request);
                    UploadManager.UploadResponse response = uploadManager.upload(uploadDetails);


                    //System.out.println("https://objectstorage."+getRegionForURL()+".oraclecloud.com/n/"+namespaceName+"/b/"+"dragon"+"/o/"+(dbName+"/"+collectionName+"/"+file.getName()).replaceAll("/", "%2F"));


                    //System.out.println(response);
                    nb++;
                }

                section.print(String.format("collection %s: loading...", collectionName));

                // if (databaseType == DatabaseType.AlwaysFreeATP) {
                try {
                    rSQLS.execute(String.format(
                            "BEGIN\n" +
                                    "    DBMS_CLOUD.COPY_COLLECTION(\n" +
                                    "        collection_name => '%s',\n" +
                                    "        credential_name => 'DRAGON_CREDENTIAL_NAME',\n" +
                                    "        file_uri_list => 'https://objectstorage.%s.oraclecloud.com/n/%s/b/dragon/o/%s/%s/*',\n" +
                                    "        format => JSON_OBJECT('recorddelimiter' value '''\\n''', 'ignoreblanklines' value 'true') );\n" +
                                    "END;\n" +
                                    "/", collectionName, getRegionForURL(), namespaceName, dbName, collectionName));
                } catch (RuntimeException re) {
                    section.printlnKO();
                    throw new CollectionNotLoadedException(collectionName, re);
                }
                /*} else {
                    // use DBMS_SCHEDULER with class HIGH...
                    try {
                        // TODO: Check for progress of load... using view USER_LOAD_OPERATIONS
                        rSQLS.execute(String.format(
                                "BEGIN\n" +
                                        "    DBMS_SCHEDULER.CREATE_JOB (\n" +
                                        "     job_name => 'LOAD_%s',\n" +
                                        "     job_type => 'PLSQL_BLOCK',\n" +
                                        "     job_action => 'BEGIN DBMS_CLOUD.COPY_COLLECTION(collection_name => ''%s'', credential_name => ''DRAGON_CREDENTIAL_NAME'', file_uri_list => ''https://objectstorage.%s.oraclecloud.com/n/%s/b/dragon/o/%s/%s/*'', format => JSON_OBJECT(''recorddelimiter'' value ''''''\\n'''''', ''ignoreblanklines'' value ''true'')); END;',\n" +
                                        "     start_date => SYSTIMESTAMP,\n" +
                                        "     enabled => TRUE,\n" +
                                        "     auto_drop => FALSE,\n" +
                                        "     job_class => 'HIGH',\n" +
                                        "     comments => 'load %s collection');\n" +
                                        "    COMMIT;\n" +
                                        "END;\n" +
                                        "/\n", collectionName, collectionName, getRegionForURL(), namespaceName, dbName, collectionName, collectionName));

                        // TODO: Check for progress of load... using view USER_LOAD_OPERATIONS
                    } catch (RuntimeException re) {
                        section.printlnKO();
                        throw new CollectionNotLoadedException(collectionName, re);
                    }
                }*/
            }
        }
    }

    private void destroyDatabase() throws OCIDatabaseTerminationFailedException, OCIDatabaseWaitForTerminationFailedException {
        section = Section.DatabaseTermination;
        section.print("checking existing databases");

        final ListAutonomousDatabasesRequest listADB = ListAutonomousDatabasesRequest.builder().compartmentId(configFile.get("compartment_id")).build();
        final ListAutonomousDatabasesResponse listADBResponse = dbClient.listAutonomousDatabases(listADB);

        boolean dbNameExists = false;
        String adbId = null;
        for (AutonomousDatabaseSummary adb : listADBResponse.getItems()) {
            //System.out.println(adb.getLifecycleState()+", "+adb.getIsFreeTier()+", "+dbName);

            if (adb.getLifecycleState() != AutonomousDatabaseSummary.LifecycleState.Terminated) {
                if (adb.getDbName().equals(dbName)) {
                    if (databaseType == DatabaseType.AlwaysFreeATP && !adb.getIsFreeTier()) continue;
                    dbNameExists = true;
                    adbId = adb.getId();
                    break;
                }
            }
        }

        if (!dbNameExists) {
            section.printlnOK("nothing to do");
        } else {
            section.print("pending");

            workRequestClient = new WorkRequestClient(provider);
            DeleteAutonomousDatabaseResponse responseTerminate = dbClient.deleteAutonomousDatabase(DeleteAutonomousDatabaseRequest.builder().autonomousDatabaseId(adbId).build());
            String workRequestId = responseTerminate.getOpcWorkRequestId();

            GetWorkRequestRequest getWorkRequestRequest = GetWorkRequestRequest.builder().workRequestId(workRequestId).build();
            boolean exit = false;
            long startTime = System.currentTimeMillis();
            float pendingProgressMove = 0f;
            do {
                GetWorkRequestResponse getWorkRequestResponse = workRequestClient.getWorkRequest(getWorkRequestRequest);
                switch (getWorkRequestResponse.getWorkRequest().getStatus()) {
                    case Succeeded:
                        section.printlnOK(getDurationSince(startTime));
                        exit = true;
                        break;
                    case Failed:
                        section.printlnKO();
                        final ListWorkRequestErrorsResponse response = workRequestClient.listWorkRequestErrors(ListWorkRequestErrorsRequest.builder().workRequestId(workRequestId).opcRequestId(getWorkRequestResponse.getOpcRequestId()).build());
                        final StringBuilder errors = new StringBuilder();
                        int i = 0;
                        for (WorkRequestError e : response.getItems()) {
                            if (i > 0) errors.append("\n");
                            errors.append(e.getMessage());
                            i++;
                        }
                        throw new OCIDatabaseTerminationFailedException(dbName, errors.toString());
                    case Accepted:
                        section.print(String.format("accepted [%s]", getDurationSince(startTime)));
                        break;
                    case InProgress:
                        section.print(String.format("in progress %.0f%% [%s]", Math.min(getWorkRequestResponse.getWorkRequest().getPercentComplete() + pendingProgressMove, 99f), getDurationSince(startTime)));
                        pendingProgressMove += Math.random() * 2f;
                        break;
                }

                sleep(1000L);

            } while (!exit);

            DatabaseWaiters waiter = dbClient.getWaiters();
            try {
                final GetAutonomousDatabaseResponse responseGet = waiter.forAutonomousDatabase(GetAutonomousDatabaseRequest.builder().autonomousDatabaseId(adbId).build(),
                        new AutonomousDatabase.LifecycleState[]{AutonomousDatabase.LifecycleState.Terminated}).execute();
            } catch (Exception e) {
                section.printlnKO();
                throw new OCIDatabaseWaitForTerminationFailedException(e);
            }
        }

        // deleting local configuration!
        final File toDelete = new File(LOCAL_CONFIGURATION_FILENAME);
        if (toDelete.exists()) {
            toDelete.delete();
        }
    }

    private void sleep(final long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException ignored) {
        }
    }

    public void close() {
        if (dbClient != null) dbClient.close();
        if (workRequestClient != null) workRequestClient.close();
        if (objectStorageClient != null) objectStorageClient.close();
        if (identityClient != null) identityClient.close();
    }

    public void displayInformation() {
        if (!info) return;

        Console.println("  . OCI profile    : " + profileName);
        Console.println("  . OCI region     : " + getRegionForURL());
        Console.println("  . OCI tenant     : " + configFile.get(CONFIG_TENANCY_ID));
        Console.println("  . OCI compartment: " + configFile.get(CONFIG_COMPARTMENT_ID));
        Console.println("  . OCI user       : " + configFile.get(CONFIG_USER));
    }
}
