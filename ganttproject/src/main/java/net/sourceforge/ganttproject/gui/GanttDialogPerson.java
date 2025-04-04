/*
GanttProject is an opensource project management tool.
Copyright (C) 2003-2011 GanttProject Team

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.sourceforge.ganttproject.gui;

import biz.ganttproject.core.calendar.GanttDaysOff;
import biz.ganttproject.core.option.*;
import biz.ganttproject.customproperty.CustomPropertyManager;
import com.google.common.collect.Lists;
import javafx.collections.FXCollections;
import net.sourceforge.ganttproject.action.CancelAction;
import net.sourceforge.ganttproject.action.OkAction;
import net.sourceforge.ganttproject.gui.DateIntervalListEditor.DateInterval;
import net.sourceforge.ganttproject.gui.DateIntervalListEditor.DefaultDateIntervalModel;
import net.sourceforge.ganttproject.gui.options.OptionsPageBuilder;
import net.sourceforge.ganttproject.gui.taskproperties.CustomColumnsPanel;
import net.sourceforge.ganttproject.language.GanttLanguage;
import net.sourceforge.ganttproject.resource.HumanResource;
import net.sourceforge.ganttproject.resource.HumanResourceManager;
import net.sourceforge.ganttproject.roles.Role;
import net.sourceforge.ganttproject.roles.RoleManager;
import net.sourceforge.ganttproject.storage.ProjectDatabase;
import net.sourceforge.ganttproject.task.TaskManager;

import javax.swing.*;
import java.awt.event.ActionEvent;

public class GanttDialogPerson {
  private final TaskManager myTaskManager;
  private final ProjectDatabase myProjectDatabase;
  private final HumanResourceManager myResourceManager;
  private boolean change;

  private HumanResource person;

  private static final GanttLanguage language = GanttLanguage.getInstance();

  private final StringOption myNameField = new DefaultStringOption("name");
  private final StringOption myPhoneField = new DefaultStringOption("colPhone");
  private final StringOption myMailField = new DefaultStringOption("colMail");
  private final MoneyOption myStandardRateField = new DefaultMoneyOption("colStandardRate");
  private final MoneyOption myTotalCostField = new DefaultMoneyOption("colTotalCost");
  private final DoubleOption myTotalLoadField = new DefaultDoubleOption("colTotalLoad");
  private final EnumerationOption myRoleField;
  private final GPOptionGroup myGroup;
  private GPOptionGroup myRateGroup;
  private final UIFacade myUIFacade;
  private final CustomPropertyManager myCustomPropertyManager;
  private ResourceAssignmentsPanel myAssignmentsPanel;


  public GanttDialogPerson(HumanResourceManager resourceManager,
                           CustomPropertyManager customPropertyManager,
                           TaskManager taskManager,
                           ProjectDatabase projectDatabase,
                           UIFacade uiFacade,
                           HumanResource person) {
    myResourceManager = resourceManager;
    myCustomPropertyManager = customPropertyManager;
    myTaskManager = taskManager;
    myUIFacade = uiFacade;
    myProjectDatabase = projectDatabase;
    this.person = person;
    Role[] enabledRoles = RoleManager.Access.getInstance().getEnabledRoles();
    String[] roleFieldValues = new String[enabledRoles.length];
    for (int i = 0; i < enabledRoles.length; i++) {
      roleFieldValues[i] = enabledRoles[i].getName();
    }
    myRoleField = new DefaultEnumerationOption<Object>("colRole", roleFieldValues);
    myGroup = new GPOptionGroup("", new GPOption[]{myNameField, myPhoneField, myMailField, myRoleField});
    myGroup.setTitled(false);

    ((GPAbstractOption)myTotalCostField).setWritable(false);
    ((GPAbstractOption)myTotalLoadField).setWritable(false);
    myRateGroup = new GPOptionGroup("resourceRate", new GPOption[] {myStandardRateField, myTotalCostField, myTotalLoadField});
  }

  public boolean result() {
    return change;
  }

  public void setVisible(boolean isVisible) {
    if (isVisible) {
      loadFields();
      OkAction okAction = new OkAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          myGroup.commit();
          okButtonActionPerformed();
        }
      };
      CancelAction cancelAction = new CancelAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
          myGroup.rollback();
          change = false;
        }
      };

      OptionsPageBuilder builder = new OptionsPageBuilder();
      OptionsPageBuilder.I18N i18n = new OptionsPageBuilder.I18N() {
        @Override
        public String getOptionLabel(GPOptionGroup group, GPOption<?> option) {
          return getValue(option.getID());
        }
      };
      builder.setI18N(i18n);

      var actions = Lists.newArrayList(okAction, cancelAction);
      PropertiesDialogKt.propertiesDialog(
        language.getCorrectedLabel("human"),
        "resourceProperties", actions, FXCollections.emptyObservableList(),
        Lists.newArrayList(
          PropertiesDialogKt.swingTab(
            language.getText("general"),
            () -> builder.buildPlanePage(new GPOptionGroup[] { myGroup, myRateGroup })
          ),
          PropertiesDialogKt.swingTab(
            language.getText("daysOff"),
            this::constructDaysOffPanel
          ),
          PropertiesDialogKt.swingTab(
            language.getText("customColumns"),
            () -> {
              var customColumnsPanel = new CustomColumnsPanel(myCustomPropertyManager, myProjectDatabase, CustomColumnsPanel.Type.RESOURCE,
                myUIFacade.getUndoManager(), person, myUIFacade.getResourceTree().getVisibleFields());
              return customColumnsPanel.getComponent();
            }
          ),
          PropertiesDialogKt.swingTab(
            language.getText("assignments"),
            () -> {
              constructAssignmentsPanel();
              return myAssignmentsPanel.getComponent();
            }
          )
        )
      );
    }
  }

  private void loadFields() {
    myNameField.setValue(person.getName());
    myPhoneField.setValue(person.getPhone());
    myMailField.setValue(person.getMail());
    Role role = person.getRole();
    if (role != null) {
      myRoleField.setValue(role.getName());
    }
    myStandardRateField.setValue(person.getStandardPayRate());
    myTotalCostField.setValue(person.getTotalCost());
    myTotalLoadField.setValue(person.getTotalLoad());
  }

  private void constructAssignmentsPanel() {
    myAssignmentsPanel = new ResourceAssignmentsPanel(person, myTaskManager);
  }

  private void okButtonActionPerformed() {
    if (person.getId() != -1) {
      // person ID is -1 when it is new one
      // i.e. before the Person dialog is closed
      myUIFacade.getUndoManager().undoableEdit("Resource properties changed", new Runnable() {
        @Override
        public void run() {
          applyChanges();
        }
      });
    } else {
      myUIFacade.getUndoManager().undoableEdit(GanttLanguage.getInstance().formatText("resource.new.description"), () -> {
        applyChanges();
        myResourceManager.add(person);
        myUIFacade.getResourceTree().setSelected(person, true);
        myUIFacade.getViewManager().getView(String.valueOf(UIFacade.RESOURCES_INDEX)).setActive(true);
      });
    }
    change = true;
  }

  private void applyChanges() {
    person.setName(myNameField.getValue());
    person.setMail(myMailField.getValue());
    person.setPhone(myPhoneField.getValue());
    Role role = findRole(myRoleField.getValue());
    if (role != null) {
      person.setRole(role);
    }
    person.getDaysOff().clear();
    for (DateInterval interval : myDaysOffModel.getIntervals()) {
      person.addDaysOff(new GanttDaysOff(interval.start, interval.getEnd()));
    }
    person.setStandardPayRate(myStandardRateField.getValue());
    myAssignmentsPanel.commit();
    // FIXME change = false;? (after applying changed they are not changes
    // anymore...)
  }

  private Role findRole(String roleName) {
    Role[] enabledRoles = RoleManager.Access.getInstance().getEnabledRoles();
    for (Role enabledRole : enabledRoles) {
      if (enabledRole.getName().equals(roleName)) {
        return enabledRole;
      }
    }
    return null;
  }

  private DefaultDateIntervalModel myDaysOffModel;

  public JPanel constructDaysOffPanel() {
    myDaysOffModel = new DateIntervalListEditor.DefaultDateIntervalModel() {
      @Override
      public int getMaxIntervalLength() {
        return 2;
      }

      @Override
      public void add(DateInterval interval) {
        super.add(interval);
      }

      @Override
      public void remove(DateInterval interval) {
        super.remove(interval);
      }
    };
    DefaultListModel daysOff = person.getDaysOff();
    for (int i = 0; i < daysOff.getSize(); i++) {
      GanttDaysOff next = (GanttDaysOff) daysOff.get(i);
      myDaysOffModel.add(DateIntervalListEditor.DateInterval.createFromModelDates(next.getStart().getTime(),
          next.getFinish().getTime()));
    }
    DateIntervalListEditor editor = new DateIntervalListEditor(myDaysOffModel);
    return editor;
  }
}
