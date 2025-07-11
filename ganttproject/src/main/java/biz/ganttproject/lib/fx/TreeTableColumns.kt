/*
Copyright 2021 BarD Software s.r.o

This file is part of GanttProject, an open-source project management tool.

GanttProject is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

GanttProject is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with GanttProject.  If not, see <http://www.gnu.org/licenses/>.
*/
package biz.ganttproject.lib.fx

import biz.ganttproject.FXUtil
import biz.ganttproject.core.table.BuiltinColumn
import biz.ganttproject.core.table.ColumnList
import javafx.beans.property.SimpleDoubleProperty
import javafx.scene.control.TreeTableColumn
import biz.ganttproject.customproperty.CustomPropertyManager
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList

/**
 * Provides a list of "built-in" columns, that is, those which are not
 * user-defined. A column may have zero width, in this case its data
 * appears as a label in some other column (example: task color).
 */
data class BuiltinColumns(
  /**
   * @return true if a column with the given id is zero width
   */
  val isZeroWidth: (columnId: String) -> Boolean,
  val allColumns: () -> List<BuiltinColumn>
) {
  fun exists(columnId: String) = allColumns().any { it.stub.id == columnId }
  fun find(columnId: String) = allColumns().find { it.stub.id == columnId }
}

/**
 * This is a sort of "column model" for the tree table. It maintains a list of
 * Column objects and loosely keeps it synchronized with the UI columns. It is not always
 * in sync: UI columns may change their width and order without changes here. However,
 * there are methods to sync this list with the UI.
 *
 * @author dbarashev@bardsoftware.com
 */
class ColumnListImpl(
        private val customPropertyManager: CustomPropertyManager,
        private val tableColumns: () -> List<TreeTableColumn<*, *>>,
        private val onColumnChange: () -> Unit = {},
        private val onColumnListChange: () -> Unit = {},
        private val builtinColumns: BuiltinColumns,
        columnList: List<ColumnList.Column> = emptyList()
) : ColumnList {

  private val columnStorage: ObservableList<ColumnList.Column> = FXCollections.observableArrayList(columnList)
  val totalWidth: Double get() = totalWidthProperty.value
  val totalWidthProperty = SimpleDoubleProperty()
  val onColumnResize = this::updateTotalWidth

  init {
    updateTotalWidth()
    columnStorage.addListener(ListChangeListener { onColumnListChange() })
  }
  override fun getSize(): Int = columnStorage.size

  override fun getField(index: Int): ColumnList.Column = columnStorage[index]

  override fun clear() = synchronized(columnStorage) {
    columnStorage.clear()
    updateTotalWidth()
  }

  override fun add(id: String, order: Int, width: Int) {
    synchronized(columnStorage) {
      (this.columnStorage.firstOrNull { it.id == id } ?: run {
        customPropertyManager.getCustomPropertyDefinition(id)?.let { def ->
          ColumnList.ColumnStub(id, def.name, true,
            if (order == -1) tableColumns().count { it.isVisible } else order,
            if (width == -1) 75 else width
          )
        }
      })?.let {
        this.columnStorage.add(it)
      }
      updateTotalWidth()
    }
  }

  override fun importData(source: ColumnList, keepVisibleColumns: Boolean) {
    val remainVisible = if (keepVisibleColumns) {
      tableColumns().filter { it.isVisible }.map { it.userData as ColumnList.Column }
    } else emptyList()

    var importedList = source.copyOf().filter { builtinColumns.exists(it.id) || customPropertyManager.getCustomPropertyDefinition(it.id)  != null }
    // Mark all columns in the imported list which should be visible because they are visible now.
    remainVisible.forEach { old -> importedList.firstOrNull { new -> new.id == old.id }?.isVisible = true }

    // If it turns out that none of the imported columns is visible, we shall do something so that the table was not empty.
    // Here we just replace the list with the default built-in columns.
    if (importedList.firstOrNull { it.isVisible } == null) {
      importedList = builtinColumns.allColumns().map { it.stub }
    }


    importedList = importedList.sortedWith { left, right ->
      // test1 places visible columns before invisible
      val test1 = (if (left.isVisible) -1 else 0) + if (right.isVisible) 1 else 0
      when {
        test1 != 0 -> test1
        // Invisible columns are compared by name (why?)
        !left.isVisible && !right.isVisible && left.name != null && right.name != null -> {
          left.name.compareTo(right.name)
        }
        // If both columns are visible and their order value happens to be the same (may happen when importing
        // one project into another) then we compare ids (what we do if they are equal too?)
        left.order == right.order -> left.id.compareTo(right.id)
        // Otherwise we use order value
        else -> left.order - right.order
      }
    }

    // Here we merge the imported list with the currently available one.
    // We maintain the invariant: the list prefix [0, idxImported) is the same in the imported list and
    // in the result list.
    synchronized(columnStorage) {
      val currentList = columnStorage.map { it }.toMutableList()
      importedList.forEachIndexed { idxImported, column ->
        val idxCurrent = currentList.indexOfFirst { it.id == column.id }
        if (idxCurrent >= 0) {
          if (idxCurrent != idxImported) {
            // Because of the invariant, it can only be greater. We will remove all
            // the columns between the imported and existing. This may be an excessive measure
            // because the removed columns may be found later in the imported list, but that's ok.
            assert(idxCurrent > idxImported) {
              "Unexpected column indices: imported=$idxImported current=$idxCurrent for column=$column"
            }
            currentList.subList(idxImported, idxCurrent).clear()
          }
          if (currentList[idxImported] != column) {
            currentList[idxImported] = ColumnList.ColumnStub(column).also {
              customPropertyManager.getCustomPropertyDefinition(it.id)?.let { def -> it.name = def.name }
              it.setOnChange {
                updateTotalWidth()
                onColumnChange()
              }
            }
          }
        } else {
          currentList.add(idxImported, ColumnList.ColumnStub(column).also {
            customPropertyManager.getCustomPropertyDefinition(it.id)?.let { def -> it.name = def.name }
            it.setOnChange {
              updateTotalWidth()
              onColumnChange()
            }
          })
        }
        assert(currentList.subList(0, idxImported) == importedList.subList(0, idxImported))
      }
      // Finally clear the remaining tail.
      if (currentList.size > importedList.size) {
        currentList.subList(importedList.size, currentList.size).clear()
      }

      columnStorage.clear()
      columnStorage.addAll(currentList)
      FXUtil.runLater {
        updateTotalWidth()
      }
    }
  }

  override fun exportData(): List<ColumnList.Column> {
    synchronized(columnStorage) {
      reloadWidthFromUi()
      return copyOf()
    }
  }

  fun columns(): List<ColumnList.Column> {
    synchronized(columnStorage) {
      return copyOf()
    }
  }

  private fun updateTotalWidth() {
    totalWidthProperty.value = columnStorage.filter { it.isVisible  }.sumOf {
      if (builtinColumns.isZeroWidth(it.id)) 0 else it.width
    }.toDouble()
  }

  fun reloadWidthFromUi() {
    synchronized(columnStorage) {
      tableColumns().forEachIndexed { index, column ->
        (column.userData as ColumnList.Column).let { userData ->
          columnStorage.firstOrNull { it.id == userData.id }?.let {
            it.order = index
            it.width = column.width.toInt()
          }
        }
      }
      updateTotalWidth()
    }
  }
}

fun ColumnList.copyOf(): List<ColumnList.Column> {
  val copy = mutableListOf<ColumnList.ColumnStub>()
  for (i in 0 until this.size) {
    copy.add(ColumnList.ColumnStub(this.getField(i)))
  }
  return copy
}
