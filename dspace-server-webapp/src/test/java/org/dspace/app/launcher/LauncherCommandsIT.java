/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.launcher;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dspace.AbstractDSpaceIntegrationTest;
import org.dspace.scripts.DSpaceRunnable;
import org.jdom2.Document;
import org.jdom2.Element;
import org.junit.Test;

/**
 * Guards {@code dspace/config/launcher.xml} against entries that resolve to nothing and against
 * commands quietly disappearing from it.
 */
public class LauncherCommandsIT extends AbstractDSpaceIntegrationTest {

    /** A placeholder, not a class: {@code dsrun} takes the class to run from the user's arguments. */
    private static final String DSRUN_PLACEHOLDER = "dsrun";

    /** The commands CLARIN adds on top of vanilla, each mapped to the class it launches. */
    private static final Map<String, String> CLARIN_COMMANDS = new LinkedHashMap<>();

    /** Every command the launcher is known to register; commands may be added, none may go missing. */
    private static final List<String> KNOWN_COMMANDS = List.of(
        "anonymize-statistics", "bitstore-migrate", "checker", "checker-emailer", "clarin-token",
        "classpath", "cleanup", "community-filiator", "create-administrator", "database",
        "doi-organiser", "dsprop", "dsrun", "embargo-lifter", "generate-sitemaps", "harvest",
        "iiif-canvas-dimensions", "index-authority", "initialize-entities", "itemupdate",
        "make-handle-config", "matomo-report-generator", "migrate-embargo", "oai", "packager",
        "rdfizer", "read", "registry-loader", "retry-tracker", "solr-export-statistics",
        "solr-import-statistics", "solr-reindex-statistics", "solr-upgrade-statistics-6x",
        "stat-general", "stat-initial", "stat-monthly", "stat-report-general", "stat-report-initial",
        "stat-report-monthly", "stats-log-converter", "stats-log-importer", "stats-util",
        "structure-builder", "test-email", "update-handle-prefix", "user", "validate-date", "version");

    static {
        CLARIN_COMMANDS.put("clarin-token", "org.dspace.administer.ClarinTokenAdministrator");
        CLARIN_COMMANDS.put("matomo-report-generator", "org.dspace.matomo.MatomoPDFExporter");
    }

    /**
     * Mirrors the two ways {@code ScriptLauncher} starts a command: a {@link DSpaceRunnable} dispatched by
     * name, or the static {@code main(String[])} of the step class.
     */
    @Test
    public void everyLauncherCommandIsRunnable() {
        List<Element> commands = commands();
        assertTrue("launcher.xml yielded " + commands.size() + " commands, so it was not read properly",
                   commands.size() > 20);

        List<String> broken = new ArrayList<>();
        for (Element command : commands) {
            String name = command.getChildText("name");
            for (Element step : command.getChildren("step")) {
                String className = step.getChildText("class");
                if (className != null && !DSRUN_PLACEHOLDER.equals(className)) {
                    broken.addAll(problemsWith(name, className));
                }
            }
        }

        assertThat("launcher.xml registers commands that ScriptLauncher cannot run:\n  "
                       + String.join("\n  ", broken), broken, empty());
    }

    /**
     * The other end of the same contract: a class can survive an upgrade while its command block does not,
     * and then nothing fails to compile and nothing can call it.
     */
    @Test
    public void everyClarinCommandIsRegistered() {
        Map<String, String> registered = new LinkedHashMap<>();
        for (Element command : commands()) {
            Element step = command.getChild("step");
            if (step != null && step.getChildText("class") != null) {
                registered.put(command.getChildText("name"), step.getChildText("class"));
            }
        }

        List<String> missing = new ArrayList<>();
        CLARIN_COMMANDS.forEach((name, className) -> {
            if (!className.equals(registered.get(name))) {
                missing.add(name + " -> " + className + " (launcher.xml has: " + registered.get(name) + ")");
            }
        });

        assertThat("dspace/config/launcher.xml no longer registers these CLARIN commands, so the classes"
                       + " implementing them cannot be launched:\n  " + String.join("\n  ", missing),
                   missing, empty());
    }

    /**
     * A command dropped from the file is simply no longer iterated by the checks above, so the set of
     * registered names is checked against the one this branch ships.
     */
    @Test
    public void noKnownLauncherCommandDisappears() {
        Set<String> registered = new LinkedHashSet<>();
        for (Element command : commands()) {
            registered.add(command.getChildText("name"));
        }

        List<String> missing = new ArrayList<>(KNOWN_COMMANDS);
        missing.removeAll(registered);

        assertThat("dspace/config/launcher.xml no longer registers these commands: " + missing,
                   missing, empty());
    }

    private List<String> problemsWith(String commandName, String className) {
        List<String> problems = new ArrayList<>();
        Class<?> target;
        try {
            target = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            problems.add(commandName + " -> " + className + " is not on the classpath");
            return problems;
        }
        if (DSpaceRunnable.class.isAssignableFrom(target)) {
            return problems;
        }
        try {
            Method main = target.getMethod("main", String[].class);
            if (!Modifier.isStatic(main.getModifiers())) {
                problems.add(commandName + " -> " + className + ".main(String[]) is not static");
            }
        } catch (NoSuchMethodException e) {
            problems.add(commandName + " -> " + className
                             + " is neither a DSpaceRunnable nor has a public main(String[])");
        }
        return problems;
    }

    private List<Element> commands() {
        Document config = ScriptLauncher.getConfig(kernelImpl);
        return config.getRootElement().getChildren("command");
    }
}
