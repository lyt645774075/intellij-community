package org.jetbrains.jps.incremental.artifacts;

import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.containers.MultiMap;
import gnu.trove.THashSet;
import gnu.trove.TIntObjectHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.builders.BuildRootDescriptor;
import org.jetbrains.jps.builders.BuildRootIndex;
import org.jetbrains.jps.builders.DirtyFilesHolder;
import org.jetbrains.jps.builders.storage.SourceToOutputMapping;
import org.jetbrains.jps.cmdline.ProjectDescriptor;
import org.jetbrains.jps.incremental.*;
import org.jetbrains.jps.incremental.artifacts.impl.ArtifactSorter;
import org.jetbrains.jps.incremental.artifacts.impl.JarsBuilder;
import org.jetbrains.jps.incremental.artifacts.instructions.*;
import org.jetbrains.jps.incremental.fs.BuildFSState;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.incremental.messages.ProgressMessage;
import org.jetbrains.jps.model.artifact.JpsArtifact;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author nik
 */
public class IncArtifactBuilder extends TargetBuilder<ArtifactRootDescriptor, ArtifactBuildTarget> {
  public static final String BUILDER_NAME = "artifacts";

  public IncArtifactBuilder() {
    super(Collections.singletonList(ArtifactBuildTargetType.INSTANCE));
  }

  @Override
  public void build(@NotNull ArtifactBuildTarget target,
                    @NotNull CompileContext context,
                    DirtyFilesHolder<ArtifactRootDescriptor, ArtifactBuildTarget> holder) throws ProjectBuildException {
    JpsArtifact artifact = target.getArtifact();
    if (StringUtil.isEmpty(artifact.getOutputPath())) {
      context.processMessage(new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.ERROR, "Cannot build '" + artifact.getName() + "' artifact: output path is not specified"));
      return;
    }
    final ProjectDescriptor pd = context.getProjectDescriptor();
    final ArtifactSorter sorter = new ArtifactSorter(pd.getModel());
    final Map<JpsArtifact, JpsArtifact> selfIncludingNameMap = sorter.getArtifactToSelfIncludingNameMap();
    final JpsArtifact selfIncluding = selfIncludingNameMap.get(artifact);
    if (selfIncluding != null) {
      String name = selfIncluding.equals(artifact) ? "it" : "'" + selfIncluding.getName() + "' artifact";
      context.processMessage(new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.ERROR, "Cannot build '" + artifact.getName() + "' artifact: " + name + " includes itself in the output layout"));
      return;
    }


    try {
      final ArtifactSourceFilesState state = pd.dataManager.getArtifactsBuildData().getOrCreateState(target, pd);
      state.ensureFsStateInitialized(pd.dataManager, context);
      final Collection<String> deletedFiles = pd.fsState.getAndClearDeletedPaths(target);
      final Map<BuildRootDescriptor, Set<File>> filesToRecompile = pd.fsState.getSourcesToRecompile(context, target);
      if (deletedFiles.isEmpty() && filesToRecompile.isEmpty()) {
        state.markUpToDate(context);
        return;
      }

      context.processMessage(new ProgressMessage("Building artifact '" + artifact.getName() + "'..."));
      final SourceToOutputMapping srcOutMapping = pd.dataManager.getSourceToOutputMap(target);
      final ArtifactOutputToSourceMapping outSrcMapping = state.getOrCreateOutSrcMapping();

      final TIntObjectHashMap<Set<String>> filesToProcess = new TIntObjectHashMap<Set<String>>();
      MultiMap<String, String> filesToDelete = new MultiMap<String, String>();
      for (String sourcePath : deletedFiles) {
        final Collection<String> outputPaths = srcOutMapping.getOutputs(sourcePath);
        if (outputPaths != null) {
          for (String outputPath : outputPaths) {
            filesToDelete.putValue(outputPath, sourcePath);
            final List<ArtifactOutputToSourceMapping.SourcePathAndRootIndex> sources = outSrcMapping.getState(outputPath);
            if (sources != null) {
              for (ArtifactOutputToSourceMapping.SourcePathAndRootIndex source : sources) {
                addFileToProcess(filesToProcess, source.getRootIndex(), source.getPath(), deletedFiles);
              }
            }
          }
        }
      }

      Set<String> changedOutputPaths = new THashSet<String>(FileUtil.PATH_HASHING_STRATEGY);
      for (Map.Entry<BuildRootDescriptor, Set<File>> entry : filesToRecompile.entrySet()) {
        int rootIndex = ((ArtifactRootDescriptor)entry.getKey()).getRootIndex();
        for (File file : entry.getValue()) {
          String sourcePath = FileUtil.toSystemIndependentName(file.getPath());
          addFileToProcess(filesToProcess, rootIndex, sourcePath, deletedFiles);
          final Collection<String> outputPaths = srcOutMapping.getOutputs(sourcePath);
          if (outputPaths != null) {
            changedOutputPaths.addAll(outputPaths);
            for (String outputPath : outputPaths) {
              filesToDelete.putValue(outputPath, sourcePath);
              final List<ArtifactOutputToSourceMapping.SourcePathAndRootIndex> sources = outSrcMapping.getState(outputPath);
              if (sources != null) {
                for (ArtifactOutputToSourceMapping.SourcePathAndRootIndex source : sources) {
                  addFileToProcess(filesToProcess, source.getRootIndex(), source.getPath(), deletedFiles);
                }
              }
            }
          }
        }
      }
      for (Set<File> files : filesToRecompile.values()) {
        for (File file : files) {
          srcOutMapping.remove(file.getPath());
        }
      }
      for (String outputPath : changedOutputPaths) {
        outSrcMapping.remove(outputPath);
      }

      deleteOutdatedFiles(filesToDelete, context, srcOutMapping, outSrcMapping);
      context.checkCanceled();

      final Set<JarInfo> changedJars = new THashSet<JarInfo>();
      for (ArtifactRootDescriptor descriptor : pd.getBuildRootIndex().getTargetRoots(target, context)) {
        context.checkCanceled();
        final Set<String> sourcePaths = filesToProcess.get(descriptor.getRootIndex());
        if (sourcePaths == null) continue;

        for (String sourcePath : sourcePaths) {
          DestinationInfo destination = descriptor.getDestinationInfo();
          if (destination instanceof ExplodedDestinationInfo) {
            descriptor.copyFromRoot(sourcePath, descriptor.getRootIndex(), destination.getOutputPath(), context,
                                    srcOutMapping, outSrcMapping);
          }
          else if (outSrcMapping.getState(destination.getOutputFilePath()) == null) {
            outSrcMapping
              .update(destination.getOutputFilePath(), Collections.<ArtifactOutputToSourceMapping.SourcePathAndRootIndex>emptyList());
            changedJars.add(((JarDestinationInfo)destination).getJarInfo());
          }
        }
      }
      context.checkCanceled();

      JarsBuilder builder = new JarsBuilder(changedJars, context, srcOutMapping, outSrcMapping);
      final boolean processed = builder.buildJars();
      if (processed && !Utils.errorsDetected(context) && !context.getCancelStatus().isCanceled()) {
        state.markUpToDate(context);
      }
    }
    catch (IOException e) {
      throw new ProjectBuildException(e);
    }
  }

  private static void addFileToProcess(TIntObjectHashMap<Set<String>> filesToProcess,
                                       final int rootIndex,
                                       final String path,
                                       Collection<String> deletedFiles) {
    if (deletedFiles.contains(path)) {
      return;
    }
    Set<String> paths = filesToProcess.get(rootIndex);
    if (paths == null) {
      paths = new THashSet<String>(FileUtil.PATH_HASHING_STRATEGY);
      filesToProcess.put(rootIndex, paths);
    }
    paths.add(path);
  }

  private static void deleteOutdatedFiles(MultiMap<String, String> filesToDelete, CompileContext context,
                                          SourceToOutputMapping srcOutMapping,
                                          ArtifactOutputToSourceMapping outSrcMapping) throws IOException {
    if (filesToDelete.isEmpty()) return;

    context.processMessage(new ProgressMessage("Deleting outdated files..."));
    int notDeletedFilesCount = 0;
    final THashSet<String> notDeletedPaths = new THashSet<String>(FileUtil.PATH_HASHING_STRATEGY);
    final THashSet<String> deletedPaths = new THashSet<String>(FileUtil.PATH_HASHING_STRATEGY);

    for (String filePath : filesToDelete.keySet()) {
      if (notDeletedPaths.contains(filePath)) {
        continue;
      }

      boolean deleted = deletedPaths.contains(filePath);
      if (!deleted) {
        deleted = FileUtil.delete(new File(FileUtil.toSystemDependentName(filePath)));
      }

      if (deleted) {
        context.getLoggingManager().getArtifactBuilderLogger().fileDeleted(filePath);
        outSrcMapping.remove(filePath);
        deletedPaths.add(filePath);
        for (String sourcePath : filesToDelete.get(filePath)) {
          srcOutMapping.removeOutput(sourcePath, filePath);
        }
      }
      else {
        notDeletedPaths.add(filePath);
        if (notDeletedFilesCount++ > 50) {
          context.processMessage(new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.WARNING, "Deletion of outdated files stopped because too many files cannot be deleted"));
          break;
        }
        context.processMessage(new CompilerMessage(BUILDER_NAME, BuildMessage.Kind.WARNING, "Cannot delete file '" + filePath + "'"));
      }
    }
  }

  @Override
  public void buildStarted(final CompileContext context) {
    context.addBuildListener(new BuildListener() {
      @Override
      public void filesGenerated(Collection<Pair<String, String>> paths) {
        BuildFSState fsState = context.getProjectDescriptor().fsState;
        BuildRootIndex rootsIndex = context.getProjectDescriptor().getBuildRootIndex();
        for (Pair<String, String> pair : paths) {
          File file = new File(pair.getFirst(), pair.getSecond());
          Collection<ArtifactRootDescriptor> descriptors = rootsIndex.findAllParentDescriptors(file, Collections.singletonList(ArtifactBuildTargetType.INSTANCE), context);
          for (ArtifactRootDescriptor descriptor : descriptors) {
            try {
              fsState.markDirty(null, file, descriptor, null);
            }
            catch (IOException ignored) {
            }
          }
        }
      }

      @Override
      public void filesDeleted(Collection<String> paths) {
        BuildFSState state = context.getProjectDescriptor().fsState;
        BuildRootIndex rootsIndex = context.getProjectDescriptor().getBuildRootIndex();
        for (String path : paths) {
          File file = new File(FileUtil.toSystemDependentName(path));
          Collection<ArtifactRootDescriptor> descriptors = rootsIndex.findAllParentDescriptors(file, Collections.singletonList(ArtifactBuildTargetType.INSTANCE), context);
          for (ArtifactRootDescriptor descriptor : descriptors) {
            state.registerDeleted(descriptor.getTarget(), file);
          }
        }
      }
    });
  }

  @Override
  public String getName() {
    return BUILDER_NAME;
  }

  @Override
  public String getDescription() {
    return "Artifacts builder";
  }
}
