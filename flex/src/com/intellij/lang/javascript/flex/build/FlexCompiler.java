package com.intellij.lang.javascript.flex.build;

import com.intellij.compiler.options.CompileStepBeforeRun;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.lang.javascript.flex.FlexBundle;
import com.intellij.lang.javascript.flex.FlexModuleType;
import com.intellij.lang.javascript.flex.FlexUtils;
import com.intellij.lang.javascript.flex.actions.AirSigningOptions;
import com.intellij.lang.javascript.flex.actions.airpackage.AirPackageProjectParameters;
import com.intellij.lang.javascript.flex.flexunit.FlexUnitRunConfiguration;
import com.intellij.lang.javascript.flex.projectStructure.model.*;
import com.intellij.lang.javascript.flex.projectStructure.model.impl.Factory;
import com.intellij.lang.javascript.flex.projectStructure.options.BCUtils;
import com.intellij.lang.javascript.flex.projectStructure.options.BuildConfigurationNature;
import com.intellij.lang.javascript.flex.projectStructure.ui.CreateHtmlWrapperTemplateDialog;
import com.intellij.lang.javascript.flex.run.BCBasedRunnerParameters;
import com.intellij.lang.javascript.flex.run.FlashRunConfiguration;
import com.intellij.lang.javascript.flex.run.FlashRunnerParameters;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.PathUtil;
import gnu.trove.THashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInput;
import java.io.IOException;
import java.util.*;

import static com.intellij.lang.javascript.flex.projectStructure.model.AirPackagingOptions.FilePathAndPathInPackage;
import static com.intellij.lang.javascript.flex.projectStructure.model.CompilerOptions.ResourceFilesMode;
import static com.intellij.lang.javascript.flex.run.FlashRunnerParameters.AirMobileRunTarget;

public class FlexCompiler implements SourceProcessingCompiler {
  private static final Logger LOG = Logger.getInstance(FlexCompiler.class.getName());
  public static final Key<Collection<Pair<Module, FlexIdeBuildConfiguration>>> MODULES_AND_BCS_TO_COMPILE =
    Key.create("modules.and.bcs.to.compile");

  @NotNull
  public ProcessingItem[] getProcessingItems(final CompileContext context) {
    saveProject(context.getProject());
    final List<ProcessingItem> itemList = new ArrayList<ProcessingItem>();

    try {
      for (final Pair<Module, FlexIdeBuildConfiguration> moduleAndBC : getModulesAndBCsToCompile(context.getCompileScope())) {
        itemList.add(new MyProcessingItem(moduleAndBC.first, moduleAndBC.second));
      }
    }
    catch (ConfigurationException e) {
      // can't happen because already validated
      throw new RuntimeException(e);
    }

    return itemList.toArray(new ProcessingItem[itemList.size()]);
  }

  private static void saveProject(final Project project) {
    Runnable runnable = new Runnable() {
      public void run() {
        project.save();
      }
    };

    if (ApplicationManager.getApplication().isDispatchThread()) {
      runnable.run();
    }
    else {
      ApplicationManager.getApplication().invokeAndWait(runnable, ModalityState.defaultModalityState());
    }
  }

  public ProcessingItem[] process(final CompileContext context, final ProcessingItem[] items) {
    // todo clear output directories if rebuild and corresponding option checked
    // todo incremental resource files copying
    new FlexResourceCompiler(context, mapModuleToBCsWithResourceFiles(items)).processResourceFiles();

    final FlexCompilerHandler flexCompilerHandler = FlexCompilerHandler.getInstance(context.getProject());
    final FlexCompilerProjectConfiguration flexCompilerConfiguration = FlexCompilerProjectConfiguration.getInstance(context.getProject());

    if (!context.isMake()) {
      flexCompilerHandler.quitCompilerShell();
      for (ProcessingItem item : items) {
        flexCompilerHandler.getCompilerDependenciesCache().markBCDirty(((MyProcessingItem)item).myModule, ((MyProcessingItem)item).myBC);
      }
    }

    if (flexCompilerConfiguration.USE_FCSH) {
      context.addMessage(CompilerMessageCategory.INFORMATION,
                         "FCSH tool is not supported yet. Please choose another compiler at File | Settings | Compiler | Flex Compiler",
                         null, -1, -1);
      return ProcessingItem.EMPTY_ARRAY;
    }
    else {
      boolean builtIn = flexCompilerConfiguration.USE_BUILT_IN_COMPILER;
      final Sdk commonSdk = getSdkIfSame(items);

      if (builtIn && commonSdk == null) {
        builtIn = false;
        flexCompilerHandler.getBuiltInFlexCompilerHandler().stopCompilerProcess();
        context.addMessage(CompilerMessageCategory.INFORMATION, FlexBundle.message("can.not.use.built.in.compiler.shell"), null, -1, -1);
      }

      context.addMessage(CompilerMessageCategory.INFORMATION,
                         FlexBundle.message(builtIn ? "using.builtin.compiler" : "using.mxmlc.compc",
                                            flexCompilerConfiguration.MAX_PARALLEL_COMPILATIONS), null, -1, -1);
      final Collection<FlexCompilationTask> compilationTasks = new ArrayList<FlexCompilationTask>();
      for (final ProcessingItem item : items) {
        final Collection<FlexIdeBuildConfiguration> dependencies = new HashSet<FlexIdeBuildConfiguration>();
        // todo add 'optimize for' dependencies
        final FlexIdeBuildConfiguration bc = ((MyProcessingItem)item).myBC;
        for (final DependencyEntry entry : bc.getDependencies().getEntries()) {
          if (entry instanceof BuildConfigurationEntry) {
            final FlexIdeBuildConfiguration dependencyBC = ((BuildConfigurationEntry)entry).findBuildConfiguration();
            if (dependencyBC != null && !dependencyBC.isSkipCompile() &&
                entry.getDependencyType().getLinkageType() != LinkageType.LoadInRuntime) {
              dependencies.add(dependencyBC);
            }
          }
        }

        compilationTasks.add(builtIn ? new BuiltInCompilationTask(((MyProcessingItem)item).myModule, bc, dependencies)
                                     : new MxmlcCompcCompilationTask(((MyProcessingItem)item).myModule, bc, dependencies));

        if (BCUtils.canHaveRuntimeStylesheets(bc)) {
          for (String cssPath : bc.getCssFilesToCompile()) {
            final VirtualFile cssFile = LocalFileSystem.getInstance().findFileByPath(cssPath);
            if (cssFile == null) continue;

            final ModifiableFlexIdeBuildConfiguration cssBC = Factory.getTemporaryCopyForCompilation(bc);
            cssBC.setMainClass(cssPath);
            cssBC.setOutputFileName(FileUtil.getNameWithoutExtension(PathUtil.getFileName(cssPath)) + ".swf");
            cssBC.setCssFilesToCompile(Collections.<String>emptyList());
            cssBC.getCompilerOptions().setResourceFilesMode(ResourceFilesMode.None);

            VirtualFile root = ProjectRootManager.getInstance(context.getProject()).getFileIndex().getSourceRootForFile(cssFile);
            if (root == null) root = ProjectRootManager.getInstance(context.getProject()).getFileIndex().getContentRootForFile(cssFile);
            final String relativePath = root == null ? null : VfsUtilCore.getRelativePath(cssFile.getParent(), root, '/');
            if (!StringUtil.isEmpty(relativePath)) {
              cssBC.setOutputFolder(cssBC.getOutputFolder() + "/" + relativePath);
            }

            compilationTasks.add(builtIn ? new BuiltInCompilationTask(((MyProcessingItem)item).myModule, cssBC, dependencies)
                                         : new MxmlcCompcCompilationTask(((MyProcessingItem)item).myModule, cssBC, dependencies));
          }
        }
      }

      if (builtIn) {
        try {
          flexCompilerHandler.getBuiltInFlexCompilerHandler().startCompilerIfNeeded(commonSdk, context);
        }
        catch (IOException e) {
          context.addMessage(CompilerMessageCategory.ERROR, e.toString(), null, -1, -1);
          return ProcessingItem.EMPTY_ARRAY;
        }
      }

      new FlexCompilationManager(context, compilationTasks).compile();

      final int activeCompilationsNumber = flexCompilerHandler.getBuiltInFlexCompilerHandler().getActiveCompilationsNumber();
      if (activeCompilationsNumber != 0) {
        LOG.error(activeCompilationsNumber + " Flex compilation(s) are not finished!");
      }

      FlexCompilerHandler.deleteTempFlexUnitFiles(context);
      return items;
    }
  }

  private static Map<Module, Collection<FlexIdeBuildConfiguration>> mapModuleToBCsWithResourceFiles(final ProcessingItem[] items) {
    final Map<Module, Collection<FlexIdeBuildConfiguration>> result = new THashMap<Module, Collection<FlexIdeBuildConfiguration>>();

    for (ProcessingItem item : items) {
      final Module module = ((MyProcessingItem)item).myModule;
      final FlexIdeBuildConfiguration bc = ((MyProcessingItem)item).myBC;
      if (!bc.isSkipCompile() && BCUtils.canHaveResourceFiles(bc.getNature()) &&
          bc.getCompilerOptions().getResourceFilesMode() != ResourceFilesMode.None) {
        Collection<FlexIdeBuildConfiguration> bcs = result.get(module);
        if (bcs == null) {
          bcs = new ArrayList<FlexIdeBuildConfiguration>();
          result.put(module, bcs);
        }
        bcs.add(bc);
      }
    }

    return result;
  }

  @SuppressWarnings("ConstantConditions") // already checked in validateConfiguration()
  @Nullable
  private static Sdk getSdkIfSame(final ProcessingItem[] items) {
    final Sdk sdk = ((MyProcessingItem)items[0]).myBC.getSdk();

    for (int i = 1; i < items.length; i++) {
      if (!sdk.equals(((MyProcessingItem)items[i]).myBC.getSdk())) {
        return null;
      }
    }

    return sdk;
  }

  @NotNull
  public String getDescription() {
    return "ActionScript Compiler";
  }

  public boolean validateConfiguration(final CompileScope scope) {
    try {
      // todo add quick fixes to ConfigurationException
      final Collection<Pair<Module, FlexIdeBuildConfiguration>> modulesAndBCsToCompile = getModulesAndBCsToCompile(scope);

      for (final Pair<Module, FlexIdeBuildConfiguration> moduleAndBC : modulesAndBCsToCompile) {
        validateConfiguration(scope, moduleAndBC.first, moduleAndBC.second);
      }

      checkSimilarOutputFiles(modulesAndBCsToCompile);
    }
    catch (ConfigurationException e) {
      final String title =
        ConfigurationException.DEFAULT_TITLE.equals(e.getTitle()) ? FlexBundle.message("project.setup.problem.title") : e.getTitle();
      Messages.showErrorDialog(e.getMessage(), title);
      return false;
    }

    return true;
  }

  private static boolean checkSimilarOutputFiles(final Collection<Pair<Module, FlexIdeBuildConfiguration>> modulesAndBCsToCompile)
    throws ConfigurationException {

    final Map<String, Pair<Module, FlexIdeBuildConfiguration>> outputPathToModuleAndBC =
      new THashMap<String, Pair<Module, FlexIdeBuildConfiguration>>();
    for (Pair<Module, FlexIdeBuildConfiguration> moduleAndBC : modulesAndBCsToCompile) {
      final FlexIdeBuildConfiguration bc = moduleAndBC.second;
      final String outputFilePath = bc.getOutputFilePath(true);
      checkOutputPathUnique(outputFilePath, moduleAndBC, outputPathToModuleAndBC);

      /*
      final String outputFolderPath = PathUtil.getParentPath(outputFilePath);
      if (bc.getTargetPlatform() == TargetPlatform.Mobile && bc.getOutputType() == OutputType.Application) {
        if (bc.getAndroidPackagingOptions().isEnabled()) {
          final String outputPath = outputFolderPath + "/" + bc.getAndroidPackagingOptions().getPackageFileName() + ".apk";
          checkOutputPathUnique(outputPath, moduleAndBC, outputPathToModuleAndBC);
        }
        if (bc.getIosPackagingOptions().isEnabled()) {
          final String outputPath = outputFolderPath + "/" + bc.getIosPackagingOptions().getPackageFileName() + ".ios";
          checkOutputPathUnique(outputPath, moduleAndBC, outputPathToModuleAndBC);
        }
      }
      */
    }
    return true;
  }

  private static void checkOutputPathUnique(final String outputPath,
                                            final Pair<Module, FlexIdeBuildConfiguration> moduleAndBC,
                                            final Map<String, Pair<Module, FlexIdeBuildConfiguration>> outputPathToModuleAndBC)
    throws ConfigurationException {
    final String caseAwarePath = SystemInfo.isFileSystemCaseSensitive ? outputPath : outputPath.toLowerCase();

    final Pair<Module, FlexIdeBuildConfiguration> existing = outputPathToModuleAndBC.put(caseAwarePath, moduleAndBC);
    if (existing != null) {
      throw new ConfigurationException(FlexBundle
                                         .message("same.output.files", moduleAndBC.second.getName(), moduleAndBC.first.getName(),
                                                  existing.second.getName(), existing.first.getName(),
                                                  FileUtil.toSystemDependentName(outputPath)));
    }
  }

  private static Collection<Pair<Module, FlexIdeBuildConfiguration>> getModulesAndBCsToCompile(final CompileScope scope)
    throws ConfigurationException {

    final Collection<Pair<Module, FlexIdeBuildConfiguration>> result = new HashSet<Pair<Module, FlexIdeBuildConfiguration>>();
    final Collection<Pair<Module, FlexIdeBuildConfiguration>> modulesAndBCsToCompile = scope.getUserData(MODULES_AND_BCS_TO_COMPILE);
    final RunConfiguration runConfiguration = CompileStepBeforeRun.getRunConfiguration(scope);

    if (modulesAndBCsToCompile != null) {
      for (Pair<Module, FlexIdeBuildConfiguration> moduleAndBC : modulesAndBCsToCompile) {
        if (!moduleAndBC.second.isSkipCompile()) {
          final FlexIdeBuildConfiguration bcWithForcedDebugStatus = forceDebugStatus(moduleAndBC.first.getProject(), moduleAndBC.second);
          result.add(Pair.create(moduleAndBC.first, bcWithForcedDebugStatus));
          appendBCDependencies(result, moduleAndBC.first, moduleAndBC.second);
        }
      }
    }
    else if (runConfiguration instanceof FlashRunConfiguration || runConfiguration instanceof FlexUnitRunConfiguration) {
      final BCBasedRunnerParameters params = runConfiguration instanceof FlashRunConfiguration
                                             ? ((FlashRunConfiguration)runConfiguration).getRunnerParameters()
                                             : ((FlexUnitRunConfiguration)runConfiguration).getRunnerParameters();
      final Pair<Module, FlexIdeBuildConfiguration> moduleAndBC;

      final Ref<RuntimeConfigurationError> exceptionRef = new Ref<RuntimeConfigurationError>();
      moduleAndBC = ApplicationManager.getApplication().runReadAction(new NullableComputable<Pair<Module, FlexIdeBuildConfiguration>>() {
        public Pair<Module, FlexIdeBuildConfiguration> compute() {
          try {
            return params.checkAndGetModuleAndBC(runConfiguration.getProject());
          }
          catch (RuntimeConfigurationError e) {
            exceptionRef.set(e);
            return null;
          }
        }
      });
      if (!exceptionRef.isNull()) {
        throw new ConfigurationException(exceptionRef.get().getMessage(),
                                         FlexBundle.message("run.configuration.0", runConfiguration.getName()));
      }

      if (!moduleAndBC.second.isSkipCompile()) {
        result.add(moduleAndBC);
        appendBCDependencies(result, moduleAndBC.first, moduleAndBC.second);
      }
    }
    else {
      for (final Module module : scope.getAffectedModules()) {
        if (ModuleType.get(module) != FlexModuleType.getInstance()) continue;
        for (final FlexIdeBuildConfiguration bc : FlexBuildConfigurationManager.getInstance(module).getBuildConfigurations()) {
          if (!bc.isSkipCompile()) {
            result.add(Pair.create(module, bc));
          }
        }
      }
    }

    return result;
  }

  private static FlexIdeBuildConfiguration forceDebugStatus(final Project project, final FlexIdeBuildConfiguration bc) {
    final boolean debug;

    if (bc.getTargetPlatform() == TargetPlatform.Mobile) {
      final AirPackageProjectParameters params = AirPackageProjectParameters.getInstance(project);
      if (bc.getAndroidPackagingOptions().isEnabled()) {
        debug = params.androidPackageType != AirPackageProjectParameters.AndroidPackageType.Release;
      }
      else {
        debug = params.iosPackageType == AirPackageProjectParameters.IOSPackageType.DebugOverNetwork;
      }
    }
    else {
      debug = false;
    }

    // must not use getTemporaryCopyForCompilation() here because additional config file must not be merged with the generated one when compiling swf for release or AIR package
    final ModifiableFlexIdeBuildConfiguration result = Factory.getCopy(bc);
    final String additionalOptions = FlexUtils.removeDebugOption(bc.getCompilerOptions().getAdditionalOptions());
    result.getCompilerOptions().setAdditionalOptions(additionalOptions + " -debug=" + String.valueOf(debug));

    return result;
  }

  private static void appendBCDependencies(final Collection<Pair<Module, FlexIdeBuildConfiguration>> modulesAndBCs,
                                           final Module module,
                                           final FlexIdeBuildConfiguration bc) throws ConfigurationException {
    for (final DependencyEntry entry : bc.getDependencies().getEntries()) {
      if (entry instanceof BuildConfigurationEntry) {
        final BuildConfigurationEntry bcEntry = (BuildConfigurationEntry)entry;

        final Module dependencyModule = bcEntry.findModule();
        final FlexIdeBuildConfiguration dependencyBC = dependencyModule == null ? null : bcEntry.findBuildConfiguration();

        if (dependencyModule == null || dependencyBC == null) {
          throw new ConfigurationException(FlexBundle.message("bc.dependency.does.not.exist", bcEntry.getBcName(), bcEntry.getModuleName(),
                                                              bc.getName(), module.getName()));
        }

        final Pair<Module, FlexIdeBuildConfiguration> dependencyModuleAndBC = Pair.create(dependencyModule, dependencyBC);
        if (!dependencyBC.isSkipCompile()) {
          if (modulesAndBCs.add(dependencyModuleAndBC)) {
            appendBCDependencies(modulesAndBCs, dependencyModule, dependencyBC);
          }
        }
      }
    }
  }

  private static void validateConfiguration(final CompileScope scope,
                                            final Module module, final FlexIdeBuildConfiguration bc) throws ConfigurationException {
    assert !bc.isSkipCompile();
    final String moduleName = module.getName();

    final Sdk sdk = bc.getSdk();
    if (sdk == null) {
      throw new ConfigurationException(FlexBundle.message("sdk.not.set.for.bc.0.of.module.1", bc.getName(), moduleName));
    }

    InfoFromConfigFile info = InfoFromConfigFile.DEFAULT;

    final String additionalConfigFilePath = bc.getCompilerOptions().getAdditionalConfigFilePath();
    if (!additionalConfigFilePath.isEmpty()) {
      final VirtualFile additionalConfigFile = LocalFileSystem.getInstance().findFileByPath(additionalConfigFilePath);
      if (additionalConfigFile == null || additionalConfigFile.isDirectory()) {
        throw new ConfigurationException(
          FlexBundle.message("additional.config.file.not.found", additionalConfigFilePath, bc.getName(), moduleName));
      }
      if (!bc.isTempBCForCompilation()) {
        info = FlexCompilerConfigFileUtil.getInfoFromConfigFile(additionalConfigFilePath);
      }
    }

    final BuildConfigurationNature nature = bc.getNature();

    if (!nature.isLib() && info.getMainClass(module) == null && bc.getMainClass().isEmpty()) {
      throw new ConfigurationException(FlexBundle.message("main.class.not.set.for.bc.0.of.module.1", bc.getName(), moduleName));
      // real main class validation is done later in CompilerConfigGenerator
    }

    if (info.getOutputFileName() == null && info.getOutputFolderPath() == null) {
      if (bc.getOutputFileName().isEmpty()) {
        throw new ConfigurationException(FlexBundle.message("output.file.name.not.set.for.bc.0.of.module.1", bc.getName(), moduleName));
      }

      if (!nature.isLib() && !bc.getOutputFileName().toLowerCase().endsWith(".swf")) {
        throw new ConfigurationException(
          FlexBundle.message("output.file.name.must.have.2.extension.for.bc.0.of.module.1", bc.getName(), moduleName, "swf"));
      }

      if (nature.isLib() && !bc.getOutputFileName().toLowerCase().endsWith(".swc")) {
        throw new ConfigurationException(
          FlexBundle.message("output.file.name.must.have.2.extension.for.bc.0.of.module.1", bc.getName(), moduleName, "swc"));
      }

      if (bc.getOutputFolder().isEmpty()) {
        throw new ConfigurationException(FlexBundle.message("output.folder.not.set.for.bc.0.of.module.1", bc.getName(), moduleName));
      }
    }

    if (nature.isWebPlatform() && nature.isApp() && bc.isUseHtmlWrapper()) {
      if (bc.getWrapperTemplatePath().isEmpty()) {
        throw new ConfigurationException(FlexBundle.message("html.template.folder.not.set.for.bc.0.of.module.1", bc.getName(), moduleName));
      }
      final VirtualFile templateDir = LocalFileSystem.getInstance().findFileByPath(bc.getWrapperTemplatePath());
      if (templateDir == null || !templateDir.isDirectory()) {
        throw new ConfigurationException(FlexBundle.message("html.template.folder.not.found.for.bc.0.of.module.1.2",
                                                            bc.getName(), moduleName, bc.getWrapperTemplatePath()));
      }
      final VirtualFile templateFile = templateDir.findChild(CreateHtmlWrapperTemplateDialog.HTML_WRAPPER_TEMPLATE_FILE_NAME);
      if (templateFile == null) {
        throw new ConfigurationException(
          FlexBundle.message("no.index.template.html.file.bc.0.of.module.1.2", bc.getName(), moduleName, bc.getWrapperTemplatePath()));
      }

      try {
        if (!VfsUtilCore.loadText(templateFile).contains(FlexCompilationUtils.SWF_MACRO)) {
          throw new ConfigurationException(FlexBundle.message("no.swf.macro.in.template.bc.0.of.module.1.2", bc.getName(), moduleName,
                                                              FileUtil.toSystemDependentName(templateFile.getPath())));
        }
      }
      catch (IOException e) {
        throw new ConfigurationException(FlexBundle.message("failed.to.load.file", templateFile.getPath(), e.getMessage()));
      }
    }

    if (BCUtils.canHaveRuntimeStylesheets(bc)) {
      for (String cssPath : bc.getCssFilesToCompile()) {
        if (!cssPath.toLowerCase().endsWith(".css")) {
          throw new ConfigurationException(FlexBundle.message("not.css.runtime.stylesheet.bc.0.of.module.1", bc.getName(), moduleName,
                                                              FileUtil.toSystemDependentName(cssPath)));
        }
        if (LocalFileSystem.getInstance().findFileByPath(cssPath) == null) {
          throw new ConfigurationException(
            FlexBundle.message("css.not.found.bc.0.of.module.1", bc.getName(), moduleName, FileUtil.toSystemDependentName(cssPath)));
        }
      }
    }

    if (nature.isLib()) {
      for (String path : bc.getCompilerOptions().getFilesToIncludeInSWC()) {
        if (LocalFileSystem.getInstance().findFileByPath(path) == null) {
          throw new ConfigurationException(FlexBundle.message("file.to.include.in.swc.not.found.bc.0.of.module.1", bc.getName(), moduleName,
                                                              FileUtil.toSystemDependentName(path)));
        }
      }
    }

    if (nature.isMobilePlatform() && nature.isApp()) {
      final RunConfiguration runConfig = CompileStepBeforeRun.getRunConfiguration(scope);
      if (runConfig instanceof FlashRunConfiguration) {
        final FlashRunnerParameters params = ((FlashRunConfiguration)runConfig).getRunnerParameters();
        if (moduleName.equals(params.getModuleName()) &&
            bc.getName().equals(params.getBCName()) &&
            params.getMobileRunTarget() == AirMobileRunTarget.AndroidDevice) {
          try {
            checkPackagingOptions(bc.getAndroidPackagingOptions());
          }
          catch (ConfigurationException e) {
            throw new ConfigurationException(FlexBundle.message("bc.0.of.module.1.2", bc.getName(), moduleName, e.getMessage()));
          }
        }
      }
    }

    // This verification is disabled because Vladimir Krivosheev has app on app dependency because he needs predictable compilation order.
    // So we do not check dependencies and ignore incompatible ones when doing highlighting and compilation.
    //checkDependencies(moduleName, bc);
  }

  private static void checkDependencies(final String moduleName, final FlexIdeBuildConfiguration bc) throws ConfigurationException {
    for (final DependencyEntry entry : bc.getDependencies().getEntries()) {
      if (entry instanceof BuildConfigurationEntry) {
        final BuildConfigurationEntry bcEntry = (BuildConfigurationEntry)entry;
        final FlexIdeBuildConfiguration dependencyBC = bcEntry.findBuildConfiguration();
        final LinkageType linkageType = bcEntry.getDependencyType().getLinkageType();

        if (dependencyBC == null) {
          throw new ConfigurationException(
            FlexBundle.message("bc.dependency.does.not.exist", bcEntry.getBcName(), bcEntry.getModuleName(), bc.getName(), moduleName));
        }

        if (!checkDependencyType(bc, dependencyBC, linkageType)) {
          throw new ConfigurationException(
            FlexBundle.message("bc.dependency.problem",
                               bc.getName(), moduleName, bc.getOutputType().getPresentableText(),
                               dependencyBC.getName(), bcEntry.getModuleName(), dependencyBC.getOutputType().getPresentableText(),
                               linkageType.getShortText()));
        }
      }
    }
  }

  public static void checkPackagingOptions(final AirPackagingOptions packagingOptions) throws ConfigurationException {
    final String device = packagingOptions instanceof AndroidPackagingOptions
                          ? "Android"
                          : packagingOptions instanceof IosPackagingOptions
                            ? "iOS"
                            : "";
    if (packagingOptions.getPackageFileName().isEmpty()) {
      throw new ConfigurationException(FlexBundle.message("package.file.name.not.set", device));
    }

    for (FilePathAndPathInPackage entry : packagingOptions.getFilesToPackage()) {
      final String fullPath = entry.FILE_PATH;
      String relPathInPackage = entry.PATH_IN_PACKAGE;
      if (relPathInPackage.startsWith("/")) {
        relPathInPackage = relPathInPackage.substring(1);
      }

      if (fullPath.isEmpty()) {
        throw new ConfigurationException(FlexBundle.message("packaging.options.empty.file.name", device));
      }

      final VirtualFile file = LocalFileSystem.getInstance().findFileByPath(fullPath);
      if (file == null) {
        throw new ConfigurationException(
          FlexBundle.message("packaging.options.file.not.found", device, FileUtil.toSystemDependentName(fullPath)));
      }

      if (relPathInPackage.length() == 0) {
        throw new ConfigurationException(FlexBundle.message("packaging.options.empty.relative.path", device));
      }

      if (file.isDirectory() && !fullPath.endsWith("/" + relPathInPackage)) {
        throw new ConfigurationException(
          FlexBundle.message("packaging.options.relative.path.not.matches", device, FileUtil.toSystemDependentName(relPathInPackage)));
      }
    }

    final AirSigningOptions signingOptions = packagingOptions.getSigningOptions();
    if (packagingOptions instanceof IosPackagingOptions) {
      final String provisioningProfilePath = signingOptions.getProvisioningProfilePath();
      if (provisioningProfilePath.isEmpty()) {
        throw new ConfigurationException(FlexBundle.message("ios.provisioning.profile.not.set"));
      }

      final VirtualFile provisioningProfile = LocalFileSystem.getInstance().findFileByPath(provisioningProfilePath);
      if (provisioningProfile == null || provisioningProfile.isDirectory()) {
        throw new ConfigurationException(
          FlexBundle.message("ios.provisioning.profile.not.found", FileUtil.toSystemDependentName(provisioningProfilePath)));
      }
    }

    final boolean tempCertificate = !(packagingOptions instanceof IosPackagingOptions) && signingOptions.isUseTempCertificate();
    if (!tempCertificate) {
      final String keystorePath = signingOptions.getKeystorePath();
      if (keystorePath.isEmpty()) {
        throw new ConfigurationException(FlexBundle.message("keystore.not.set", device));
      }

      final VirtualFile keystore = LocalFileSystem.getInstance().findFileByPath(keystorePath);
      if (keystore == null || keystore.isDirectory()) {
        throw new ConfigurationException(FlexBundle.message("keystore.not.found", device, FileUtil.toSystemDependentName(keystorePath)));
      }
    }
  }

  public static boolean checkDependencyType(final FlexIdeBuildConfiguration bc,
                                            final FlexIdeBuildConfiguration dependencyBC,
                                            final LinkageType linkageType) {
    final boolean ok;

    switch (dependencyBC.getOutputType()) {
      case Application:
        ok = false;
        break;
      case RuntimeLoadedModule:
        ok = bc.getOutputType() == OutputType.Application && linkageType == LinkageType.LoadInRuntime;
        break;
      case Library:
        ok = ArrayUtil.contains(linkageType, LinkageType.getSwcLinkageValues());
        break;
      default:
        assert false;
        ok = false;
    }

    return ok;
  }

  public ValidityState createValidityState(final DataInput in) throws IOException {
    return new EmptyValidityState();
  }

  static boolean isSourceFile(final VirtualFile file) {
    final String ext = file.getExtension();
    return ext != null && (ext.equalsIgnoreCase("as") || ext.equalsIgnoreCase("mxml") || ext.equalsIgnoreCase("fxg"));
  }

  private static class MyProcessingItem implements ProcessingItem {
    private final Module myModule;
    private final FlexIdeBuildConfiguration myBC;

    private MyProcessingItem(final Module module, final FlexIdeBuildConfiguration bc) {
      myModule = module;
      myBC = bc;
    }

    @NotNull
    public VirtualFile getFile() {
      return myModule.getModuleFile();
    }

    public ValidityState getValidityState() {
      return new EmptyValidityState();
    }
  }
}
