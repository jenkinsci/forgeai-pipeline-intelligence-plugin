package io.forgeai.jenkins.steps;

import hudson.remoting.VirtualChannel;
import jenkins.MasterToSlaveFileCallable;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Runs on the agent node to list the top-level workspace structure.
 */
public class DirectoryTreeCallable extends MasterToSlaveFileCallable<String> {
    private static final long serialVersionUID = 1L;

    @Override
    public String invoke(File dir, VirtualChannel channel) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return "(empty)";
        return Arrays.stream(files)
                .map(f -> (f.isDirectory() ? "[DIR] " : "      ") + f.getName())
                .sorted()
                .collect(Collectors.joining("\n"));
    }
}
