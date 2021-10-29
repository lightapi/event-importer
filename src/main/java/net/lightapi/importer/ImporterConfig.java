package net.lightapi.importer;

public class ImporterConfig {
    public static String CONFIG_NAME = "importer";

    String filename;
    String bootstrap;

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(String bootstrap) {
        this.bootstrap = bootstrap;
    }

}
