package com.octopus.bamboo.plugins.task.promoterelease;

import com.atlassian.bamboo.process.ExternalProcessBuilder;
import com.atlassian.bamboo.process.ProcessService;
import com.atlassian.bamboo.task.*;
import com.atlassian.bamboo.v2.build.agent.capability.Capability;
import com.atlassian.bamboo.v2.build.agent.capability.CapabilityContext;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.utils.process.ExternalProcess;
import com.octopus.constants.OctoConstants;
import com.octopus.services.CommonTaskService;
import edu.emory.mathcs.backport.java.util.Arrays;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.types.Commandline;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Task used to deploy a release
 */
@Component
@ExportAsService({PromoteReleaseTask.class})
@Named("promoteReleaseTask")
public class PromoteReleaseTask implements TaskType {
    private static final Logger LOGGER = LoggerFactory.getLogger(PromoteReleaseTask.class);
    @ComponentImport
    private final ProcessService processService;
    @ComponentImport
    private final CapabilityContext capabilityContext;
    private final CommonTaskService commonTaskService;

    /**
     * Constructor. Params are injected by Spring under normal usage.
     *
     * @param feignService      The service that is used to interact with the API
     * @param commonTaskService The service used for common task operations
     */
    @Inject
    public PromoteReleaseTask(@NotNull final ProcessService processService,
                              @NotNull final CapabilityContext capabilityContext,
                              @NotNull final CommonTaskService commonTaskService) {
        checkNotNull(processService, "processService cannot be null");
        checkNotNull(capabilityContext, "capabilityContext cannot be null");
        checkNotNull(commonTaskService, "commonTaskService cannot be null");

        this.processService = processService;
        this.capabilityContext = capabilityContext;
        this.commonTaskService = commonTaskService;
    }

    @NotNull
    @Override
    public TaskResult execute(@NotNull final TaskContext taskContext) throws TaskException {

        final String serverUrl = taskContext.getConfigurationMap().get(OctoConstants.SERVER_URL);
        final String apiKey = taskContext.getConfigurationMap().get(OctoConstants.API_KEY);
        final String projectName = taskContext.getConfigurationMap().get(OctoConstants.PROJECT_NAME);
        final String promoteFrom = taskContext.getConfigurationMap().get(OctoConstants.PROMOTE_FROM_NAME);
        final String promoteTo = taskContext.getConfigurationMap().get(OctoConstants.PROMOTE_TO_NAME);
        final String loggingLevel = taskContext.getConfigurationMap().get(OctoConstants.VERBOSE_LOGGING);
        final Boolean verboseLogging = BooleanUtils.isTrue(BooleanUtils.toBooleanObject(loggingLevel));
        final String deploymentProgress = taskContext.getConfigurationMap().get(OctoConstants.SHOW_DEPLOYMENT_PROGRESS_KEY);
        final Boolean deploymentProgressEnabled = BooleanUtils.isTrue(BooleanUtils.toBooleanObject(deploymentProgress));
        final String additionalArgs = taskContext.getConfigurationMap().get(OctoConstants.ADDITIONAL_COMMAND_LINE_ARGS_NAME);

        checkState(StringUtils.isNotBlank(serverUrl), "OCTOPUS-BAMBOO-INPUT-ERROR-0002: Octopus URL can not be blank");
        checkState(StringUtils.isNotBlank(apiKey), "OCTOPUS-BAMBOO-INPUT-ERROR-0002: API key can not be blank");
        checkState(StringUtils.isNotBlank(projectName), "OCTOPUS-BAMBOO-INPUT-ERROR-0002: Project name can not be blank");
        checkState(StringUtils.isNotBlank(promoteFrom), "OCTOPUS-BAMBOO-INPUT-ERROR-0002: Promote from can not be blank");
        checkState(StringUtils.isNotBlank(promoteTo), "OCTOPUS-BAMBOO-INPUT-ERROR-0002: Promote to can not be blank");

        /*
            Build up the commands to be passed to the octopus cli
         */
        final List<String> commands = new ArrayList<String>();

        commands.add("deploy-release");

        commands.add("--server");
        commands.add(serverUrl);

        commands.add("--apiKey");
        commands.add(apiKey);

        commands.add("--project");
        commands.add(projectName);

        commands.add("--from");
        commands.add(promoteFrom);

        commands.add("--to");
        commands.add(promoteTo);

        if (verboseLogging) {
            commands.add("--debug");
        }

        if (deploymentProgressEnabled) {
            commands.add("--progress");
        }

        if (StringUtils.isNotBlank(additionalArgs)) {
            final String myArgs[] = Commandline.translateCommandline(additionalArgs);
            commands.addAll(Arrays.asList(myArgs));
        }

        for (final Capability cap : capabilityContext.getCapabilitySet().getCapabilities()) {
            if (cap.getKey().startsWith(OctoConstants.OCTOPUS_CLI_CAPABILITY)) {
                commands.add(0, cap.getValue());

                final ExternalProcess process = processService.createProcess(taskContext,
                        new ExternalProcessBuilder()
                                .command(commands)
                                .workingDirectory(taskContext.getWorkingDirectory()));

                process.execute();

                if (process.getHandler().getExitCode() != 0) {
                    commonTaskService.logError(
                            taskContext,
                            "Failed to execute the Octopus Command line client. "
                                    + "If the error message mentions missing library files "
                                    + "(like unwind or icu) make sure you have installed all "
                                    + "the dependencies that are documented at "
                                    + "https://www.microsoft.com/net/core");
                }

                return TaskResultBuilder.create(taskContext)
                        .checkReturnCode(process, 0)
                        .build();
            }
        }

        commonTaskService.logError(
                taskContext,
                "You need to define the Octopus CLI executable server capability in the Bamboo administration page");
        return TaskResultBuilder.create(taskContext).failed().build();
    }
}