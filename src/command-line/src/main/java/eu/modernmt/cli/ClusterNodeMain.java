package eu.modernmt.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.modernmt.cli.log4j.Log4jConfiguration;
import eu.modernmt.cluster.ClusterNode;
import eu.modernmt.cluster.EmbeddedService;
import eu.modernmt.config.*;
import eu.modernmt.config.xml.XMLConfigBuilder;
import eu.modernmt.engine.Engine;
import eu.modernmt.facade.ModernMT;
import eu.modernmt.io.DefaultCharset;
import eu.modernmt.rest.RESTServer;
import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Created by davide on 22/04/16.
 * Updated by andrearossi on 18/04/16.
 */
public class ClusterNodeMain {

    /**
     * This class represents the list of arguments that
     * can be passed to the ClusterNodeMain main
     * by the Python module that invokes it during MMT START
     */
    private static class Args {

        /* Define the name and types of the arguments as Option objects*/
        private static final Options cliOptions;

        static {
            Option engine = Option.builder("e").longOpt("engine").hasArg().required().build();
            Option statusFile = Option.builder().longOpt("status-file").hasArg().required().build();
            Option logsFolder = Option.builder().longOpt("logs").hasArg().required().build();

            Option apiPort = Option.builder("a").longOpt("api-port").hasArg().type(Integer.class).required(false).build();
            Option clusterPort = Option.builder("p").longOpt("cluster-port").hasArg().type(Integer.class).required(false).build();
            Option datastreamPort = Option.builder().longOpt("datastream-port").hasArg().required(false).build();
            Option databasePort = Option.builder().longOpt("db-port").hasArg().required(false).build();

            Option leader = Option.builder().longOpt("leader").hasArg().required(false).build();

            Option verbosity = Option.builder("v").longOpt("verbosity").hasArg().type(Integer.class).required(false).build();

            cliOptions = new Options();
            cliOptions.addOption(engine);
            cliOptions.addOption(apiPort);
            cliOptions.addOption(clusterPort);
            cliOptions.addOption(statusFile);
            cliOptions.addOption(verbosity);
            cliOptions.addOption(leader);
            cliOptions.addOption(logsFolder);
            cliOptions.addOption(datastreamPort);
            cliOptions.addOption(databasePort);
        }

        public final String engine;
        public final File statusFile;
        public final File logsFolder;
        public final int verbosity;
        public final NodeConfig config;

        /**
         * This builder parses the command line arguments passed by the Python module
         * into the Options object statically defined by Args.
         * After that, it reads the various options from the Options object
         * and stores them in the corresponding instance variables.
         * <p>
         * Finally, it uses the obtained options to create a new NodeConfig.
         * The Nodeconfig is initially generated by parsing the engine.xconf file,
         * and then its values are overwritten if necessary with the cli values
         *
         * @param args the arguments passed by the Python module
         *             when invoking the ClusterNodeMain main
         * @throws ParseException
         * @throws ConfigException
         */
        public Args(String[] args) throws ParseException, ConfigException {
            CommandLineParser parser = new DefaultParser();
            CommandLine cli = parser.parse(cliOptions, args);

            this.engine = cli.getOptionValue("engine");
            this.statusFile = new File(cli.getOptionValue("status-file"));
            this.logsFolder = new File(cli.getOptionValue("logs"));

            String verbosity = cli.getOptionValue("verbosity");
            this.verbosity = verbosity == null ? 1 : Integer.parseInt(verbosity);

            // read the engine.xconf file
            this.config = XMLConfigBuilder.build(Engine.getConfigFile(this.engine));
            this.config.getEngineConfig().setName(this.engine);

            // Create the config objects based on the engine.xconf file
            NetworkConfig netConfig = this.config.getNetworkConfig();
            DataStreamConfig streamConfig = this.config.getDataStreamConfig();
            DatabaseConfig dbConfig = this.config.getDatabaseConfig();
            ApiConfig apiConfig = netConfig.getApiConfig();
            JoinConfig joinConfig = netConfig.getJoinConfig();


            // ~~~~~~~~~~~~~~~~ PORTS MANAGEMENT ~~~~~~~~~~~~~~~~~~~
            // If the port [passed by command line] is not null
            // write it in the suitable config: it is the port to use.
            // Else, if it is null use the the port already in the config:
            // it is the port parsed from the engine.xconf file,
            // so it is either a manually-chosen port or the default value
            // (if the user didn't update the engine.xconf file)
            String port = cli.getOptionValue("cluster-port");
            if (port != null)
                netConfig.setPort(Integer.parseInt(port));
            String apiPort = cli.getOptionValue("api-port");
            if (apiPort != null)
                apiConfig.setPort(Integer.parseInt(apiPort));
            String datastreamPort = cli.getOptionValue("datastream-port");
            if (datastreamPort != null)
                streamConfig.setPort(Integer.parseInt(datastreamPort));
            String databasePort = cli.getOptionValue("db-port");
            if (databasePort != null)
                dbConfig.setPort(Integer.parseInt(databasePort));


            // ~~~~~~~~~~~~~~~~ MEMBERS MANAGEMENT ~~~~~~~~~~~~~~~~~~~
            // Members are other nodes in the MMT cluster.
            // If this node is to join an MMT cluster, it must contact
            // a member of the cluster as an entry point.

            // If a leader was passed by command line:
            //  - for the cluster join task, put it in an empty Member array:
            //    it is the only cluster member that this node will try to join to.
            //  - since the leader also hosts the database and datastream,
            //    set the datastreamconfig and databaseconfig host as the leader
            //
            // Assume that all the ports that the leader uses
            // (cluster, datastream and db ports) are the same as this node.
            // If they are not, then the configuration is wrong.

            // If no leader is passed by command line, do nothing:
            // this way the config object will use the members list parsed
            // from the engine.xconf file and try to join those members in order.

            // NOTE: In this case there may or may not be a cluster leader.
            // so just use the database and datastream hosts and ports
            // set in the engine.xconf file.
            String leader = cli.getOptionValue("leader");
            if (leader != null) {

                JoinConfig.Member[] members = new JoinConfig.Member[1];
                members[0] = new JoinConfig.Member(leader, netConfig.getPort());
                joinConfig.setMembers(members);

                this.config.getDataStreamConfig().setHost(leader);
                this.config.getDatabaseConfig().setHost(leader);
            }
        }
    }

    /**
     * This main method (usually launched by Python)
     * is employed to start an already created and trained mmt node
     *
     * @param _args the command line args for the node start
     * @throws Throwable
     */
    public static void main(String[] _args) throws Throwable {
        // parse the arguments
        Args args = new Args(_args);

        Log4jConfiguration.setup(args.verbosity, args.logsFolder);

        FileStatusListener listener = new FileStatusListener(args.statusFile,
                args.config);

        try {
            ModernMT.start(args.config, listener);

            ApiConfig apiConfig = args.config.getNetworkConfig().getApiConfig();

            if (apiConfig.isEnabled()) {
                RESTServer.ServerOptions options = new RESTServer.ServerOptions(apiConfig.getPort());
                options.contextPath = apiConfig.getApiRoot();

                RESTServer restServer = new RESTServer(options);
                restServer.start();
            }

            listener.updateStatus(ClusterNode.Status.READY).store();

        } catch (Throwable e) {
            listener.onError();
            throw e;
        }
    }

    /**
     * A FileStatusListener subscribes to a ClusterNode and,
     * when its properties change, updates the properties file
     */
    private static class FileStatusListener implements ClusterNode.StatusListener {

        private final File file;
        private final JsonObject properties;

        /**
         * This constructor employs the Nodeconfig resulting from
         * the read of the engine.xconf file.
         * and uses it to initialize the JSON structures
         * that will be later written in the node.properties file.
         * <p>
         * - cluster port
         * - api config (root and port),
         * - database (host and port)
         * - datastream (host and port)
         *
         * @param file   the node.properties JSON file
         *               in which to store the definitive configuration
         * @param config the configuration read from the engine.xconf file
         */
        public FileStatusListener(File file, NodeConfig config) {
            this.file = file;

            NetworkConfig netConfig = config.getNetworkConfig();
            ApiConfig apiConfig = netConfig.getApiConfig();
            DatabaseConfig dbConfig = config.getDatabaseConfig();
            DataStreamConfig streamConfig = config.getDataStreamConfig();

            this.properties = new JsonObject();

            if (apiConfig.isEnabled()) {
                JsonObject api = new JsonObject();
                api.addProperty("port", apiConfig.getPort());
                String root = apiConfig.getApiRoot();
                if (root != null)
                    api.addProperty("root", root);
                this.properties.add("api", api);
            }

            if (dbConfig.isEnabled()) {
                JsonObject db = new JsonObject();
                db.addProperty("port", dbConfig.getPort());
                db.addProperty("host", dbConfig.getHost());
                this.properties.add("database", db);
            }

            if (streamConfig.isEnabled()) {
                JsonObject stream = new JsonObject();
                stream.addProperty("port", streamConfig.getPort());
                stream.addProperty("host", streamConfig.getHost());
                this.properties.add("datastream", stream);
            }

            this.properties.addProperty("cluster_port", netConfig.getPort());
        }

        /**
         * Subscriber method: when the target ClusterNode changes its status,
         * the embedded services and the status in the properties file must be updated
         *
         * @param node           the clusternode that publishes the update
         * @param currentStatus  the new status of the clusternode
         * @param previousStatus the previous status of the clusternode
         */
        @Override
        public void onStatusChanged(ClusterNode node, ClusterNode.Status currentStatus, ClusterNode.Status previousStatus) {
            this.updateServices(node.getServices());

            if (currentStatus == ClusterNode.Status.READY)
                return; // Wait for REST Api to be ready

            this.updateStatus(currentStatus).store();
        }

        /**
         * In case of error, the new status must be "ERROR"*
         */
        public void onError() {
            this.updateStatus("ERROR").store();
        }

        /**
         * Update the embedded services in local properties
         *
         * @param services the list of embedded services of the ClusterNode
         * @return this very FileStatusListener
         */
        public FileStatusListener updateServices(List<EmbeddedService> services) {
            JsonArray array = new JsonArray();

            for (EmbeddedService service : services) {
                for (Process process : service.getSubprocesses()) {
                    int pid = getPid(process);
                    if (pid > 0)
                        array.add(pid);
                }
            }

            this.properties.add("embedded_services", array);
            return this;
        }


        /**
         * Update the status in local properties
         *
         * @param status the new status of the ClusterNode
         * @return this very FileStatusListener
         */
        public FileStatusListener updateStatus(ClusterNode.Status status) {
            this.updateStatus(status.toString());
            return this;
        }

        /**
         * Update the status in local properties
         *
         * @param status the new status of the ClusterNode
         * @return this very FileStatusListener
         */
        private FileStatusListener updateStatus(String status) {
            this.properties.addProperty("status", status);
            return this;
        }

        /**
         * Overwrite the node.properties file with the local properties
         */
        private void store() {
            try {
                FileUtils.write(file, this.properties.toString(), DefaultCharset.get(), false);
            } catch (IOException e) {
                // Nothing to do
            }
        }
    }

    /**
     * This method gets the PID of a Process
     *
     * @param process the process to get the PID of
     * @return the obtained pid
     */
    private static int getPid(Process process) {
        // Awful, horrible code in order to get PID from a process.
        // Yes, Java has no public API for that! ...until Java 9
        Class<?> clazz = process.getClass();

        try {
            Field pidField = clazz.getDeclaredField("pid");
            pidField.setAccessible(true);

            return pidField.getInt(process);
        } catch (Throwable e) {
            // Not good
            return -1;
        }
    }
}