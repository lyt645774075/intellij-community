package com.intellij.ui.tabs;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.FileColorManager;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * @author spleaner
 */
public class FileColorsConfigurable implements Configurable {
  private Project myProject;
  private FileColorsConfigurablePanel myPanel;

  public FileColorsConfigurable(@NotNull final Project project) {
    myProject = project;
  }

  @Nls
  public String getDisplayName() {
    return "File Colors";
  }

  public Icon getIcon() {
    return null;
  }

  public String getHelpTopic() {
    return null;
  }

  public JComponent createComponent() {
    if (myPanel == null) {
      myPanel = new FileColorsConfigurablePanel((FileColorManagerImpl) FileColorManager.getInstance(myProject));
    }

    return myPanel;
  }

  public boolean isModified() {
    return myPanel.isModified();
  }

  public void apply() throws ConfigurationException {
    myPanel.apply();
  }

  public void reset() {
    myPanel.reset();
  }

  public void disposeUIResources() {
    Disposer.dispose(myPanel);
    myPanel = null;
  }
}
