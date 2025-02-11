/*
 * Copyright (c) 2019 Livio, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the
 * distribution.
 *
 * Neither the name of the Livio Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.smartdevicelink.managers.screen.menu;

import androidx.annotation.NonNull;

import com.smartdevicelink.managers.BaseSubManager;
import com.smartdevicelink.managers.CompletionListener;
import com.smartdevicelink.managers.ISdl;
import com.smartdevicelink.managers.ManagerUtility;
import com.smartdevicelink.managers.file.FileManager;
import com.smartdevicelink.managers.file.MultipleFileCompletionListener;
import com.smartdevicelink.managers.file.filetypes.SdlArtwork;
import com.smartdevicelink.managers.lifecycle.OnSystemCapabilityListener;
import com.smartdevicelink.managers.lifecycle.SystemCapabilityManager;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCNotification;
import com.smartdevicelink.proxy.RPCRequest;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.rpc.AddCommand;
import com.smartdevicelink.proxy.rpc.AddSubMenu;
import com.smartdevicelink.proxy.rpc.DeleteCommand;
import com.smartdevicelink.proxy.rpc.DeleteSubMenu;
import com.smartdevicelink.proxy.rpc.DisplayCapability;
import com.smartdevicelink.proxy.rpc.MenuParams;
import com.smartdevicelink.proxy.rpc.OnCommand;
import com.smartdevicelink.proxy.rpc.OnHMIStatus;
import com.smartdevicelink.proxy.rpc.SdlMsgVersion;
import com.smartdevicelink.proxy.rpc.SetGlobalProperties;
import com.smartdevicelink.proxy.rpc.ShowAppMenu;
import com.smartdevicelink.proxy.rpc.WindowCapability;
import com.smartdevicelink.proxy.rpc.enums.DisplayType;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.ImageFieldName;
import com.smartdevicelink.proxy.rpc.enums.PredefinedWindows;
import com.smartdevicelink.proxy.rpc.enums.SystemCapabilityType;
import com.smartdevicelink.proxy.rpc.enums.SystemContext;
import com.smartdevicelink.proxy.rpc.enums.TextFieldName;
import com.smartdevicelink.proxy.rpc.listeners.OnMultipleRequestListener;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener;
import com.smartdevicelink.util.DebugTool;

import org.json.JSONException;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

abstract class BaseMenuManager extends BaseSubManager {
    private static final String TAG = "BaseMenuManager";
    private static final int KEEP = 0;
    private static final int MARKED_FOR_ADDITION = 1;
    private static final int MARKED_FOR_DELETION = 2;

    private final WeakReference<FileManager> fileManager;

    List<MenuCell> menuCells, waitingUpdateMenuCells, oldMenuCells, keepsNew, keepsOld;
    List<RPCRequest> inProgressUpdate;
    DynamicMenuUpdatesMode dynamicMenuUpdatesMode;
    MenuConfiguration menuConfiguration;
    SdlMsgVersion sdlMsgVersion;
    private String displayType;

    boolean waitingOnHMIUpdate;
    private boolean hasQueuedUpdate;
    HMILevel currentHMILevel;

    OnRPCNotificationListener hmiListener, commandListener;
    OnSystemCapabilityListener onDisplaysCapabilityListener;
    WindowCapability defaultMainWindowCapability;

    private static final int MAX_ID = 2000000000;
    private static final int parentIdNotFound = MAX_ID;
    private static final int menuCellIdMin = 1;
    int lastMenuId;

    SystemContext currentSystemContext;

    BaseMenuManager(@NonNull ISdl internalInterface, @NonNull FileManager fileManager) {

        super(internalInterface);

        // Set up some Vars
        this.fileManager = new WeakReference<>(fileManager);
        currentSystemContext = SystemContext.SYSCTXT_MAIN;
        currentHMILevel = HMILevel.HMI_NONE;
        lastMenuId = menuCellIdMin;
        dynamicMenuUpdatesMode = DynamicMenuUpdatesMode.ON_WITH_COMPAT_MODE;
        sdlMsgVersion = internalInterface.getSdlMsgVersion();

        addListeners();
    }

    @Override
    public void start(CompletionListener listener) {
        transitionToState(READY);
        super.start(listener);
    }

    @Override
    public void dispose() {

        lastMenuId = menuCellIdMin;
        menuCells = null;
        oldMenuCells = null;
        currentHMILevel = null;
        currentSystemContext = SystemContext.SYSCTXT_MAIN;
        dynamicMenuUpdatesMode = DynamicMenuUpdatesMode.ON_WITH_COMPAT_MODE;
        defaultMainWindowCapability = null;
        inProgressUpdate = null;
        hasQueuedUpdate = false;
        waitingOnHMIUpdate = false;
        waitingUpdateMenuCells = null;
        keepsNew = null;
        keepsOld = null;
        menuConfiguration = null;
        sdlMsgVersion = null;

        // remove listeners
        internalInterface.removeOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, hmiListener);
        internalInterface.removeOnRPCNotificationListener(FunctionID.ON_COMMAND, commandListener);
        if (internalInterface.getSystemCapabilityManager() != null) {
            internalInterface.getSystemCapabilityManager().removeOnSystemCapabilityListener(SystemCapabilityType.DISPLAYS, onDisplaysCapabilityListener);
        }

        super.dispose();
    }

    // SETTERS

    public void setDynamicUpdatesMode(@NonNull DynamicMenuUpdatesMode value) {
        this.dynamicMenuUpdatesMode = value;
    }

    /**
     * Creates and sends all associated Menu RPCs
     *
     * @param cells - the menu cells that are to be sent to the head unit, including their sub-cells.
     */
    public void setMenuCells(@NonNull List<MenuCell> cells) {

        // Create a deep copy of the list so future changes by developers don't affect the algorithm logic
        List<MenuCell> clonedCells = cloneMenuCellsList(cells);

        // If we're running on a connection < RPC 7.1, we need to de-duplicate cells because presenting them will fail if we have the same cell primary text.
        if (clonedCells != null && internalInterface.getSdlMsgVersion() != null
                && (internalInterface.getSdlMsgVersion().getMajorVersion() < 7
                || (internalInterface.getSdlMsgVersion().getMajorVersion() == 7 && internalInterface.getSdlMsgVersion().getMinorVersion() == 0))) {
            addUniqueNamesToCells(clonedCells);
        }

        if (currentHMILevel == null || currentHMILevel.equals(HMILevel.HMI_NONE) || currentSystemContext.equals(SystemContext.SYSCTXT_MENU)) {
            // We are in NONE or the menu is in use, bail out of here
            waitingOnHMIUpdate = true;
            waitingUpdateMenuCells = new ArrayList<>();
            if (clonedCells != null && !clonedCells.isEmpty()) {
                waitingUpdateMenuCells.addAll(clonedCells);
            }
            return;
        }
        waitingOnHMIUpdate = false;

        // Update our Lists
        // set old list
        if (menuCells != null) {
            oldMenuCells = new ArrayList<>(menuCells);
        }
        // copy new list
        menuCells = new ArrayList<>();
        if (clonedCells != null && !clonedCells.isEmpty()) {
            menuCells.addAll(clonedCells);
        }

        // Check for cell lists with completely duplicate information, or any duplicate voiceCommands and return if it fails (logs are in the called method).
        if (!menuCellsAreUnique(menuCells, new ArrayList<String>())) {
            return;
        }

        // Upload the Artworks
        List<SdlArtwork> artworksToBeUploaded = findAllArtworksToBeUploadedFromCells(menuCells);
        if (artworksToBeUploaded.size() > 0 && fileManager.get() != null) {
            fileManager.get().uploadArtworks(artworksToBeUploaded, new MultipleFileCompletionListener() {
                @Override
                public void onComplete(Map<String, String> errors) {

                    if (errors != null && errors.size() > 0) {
                        DebugTool.logError(TAG, "Error uploading Menu Artworks: " + errors.toString());
                    } else {
                        DebugTool.logInfo(TAG, "Menu Artworks Uploaded");
                    }
                    // proceed
                    updateMenuAndDetermineBestUpdateMethod();
                }
            });
        } else {
            // No Artworks to be uploaded, send off
            updateMenuAndDetermineBestUpdateMethod();
        }
    }

    /**
     * Returns current list of menu cells
     *
     * @return a List of Currently set menu cells
     */
    public List<MenuCell> getMenuCells() {

        if (menuCells != null) {
            return menuCells;
        } else if (waitingOnHMIUpdate && waitingUpdateMenuCells != null) {
            // this will keep from returning null if the menu list is set and we are pending HMI FULL
            return waitingUpdateMenuCells;
        } else {
            return null;
        }
    }

    /**
     * @return The currently set DynamicMenuUpdatesMode. It defaults to ON_WITH_COMPAT_MODE
     */
    public DynamicMenuUpdatesMode getDynamicMenuUpdatesMode() {
        return this.dynamicMenuUpdatesMode;
    }

    // OPEN MENU RPCs

    /**
     * Opens the Main Menu
     */
    public boolean openMenu() {

        if (sdlMsgVersion.getMajorVersion() < 6) {
            DebugTool.logWarning(TAG, "Menu opening is only supported on head units with RPC spec version 6.0.0 or later. Currently connected head unit RPC spec version is: " + sdlMsgVersion.getMajorVersion() + "." + sdlMsgVersion.getMinorVersion() + "." + sdlMsgVersion.getPatchVersion());
            return false;
        }

        ShowAppMenu showAppMenu = new ShowAppMenu();
        showAppMenu.setOnRPCResponseListener(new OnRPCResponseListener() {
            @Override
            public void onResponse(int correlationId, RPCResponse response) {
                if (response.getSuccess()) {
                    DebugTool.logInfo(TAG, "Open Main Menu Request Successful");
                } else {
                    DebugTool.logError(TAG, "Open Main Menu Request Failed");
                }
            }
        });
        internalInterface.sendRPC(showAppMenu);
        return true;
    }

    /**
     * Opens a subMenu. The cell you pass in must be constructed with {@link MenuCell(String,SdlArtwork,List)}
     *
     * @param cell - A <Strong>SubMenu</Strong> cell whose sub menu you wish to open
     */
    public boolean openSubMenu(@NonNull MenuCell cell) {

        if (sdlMsgVersion.getMajorVersion() < 6) {
            DebugTool.logWarning(TAG, "Sub menu opening is only supported on head units with RPC spec version 6.0.0 or later. Currently connected head unit RPC spec version is: " + sdlMsgVersion.getMajorVersion() + "." + sdlMsgVersion.getMinorVersion() + "." + sdlMsgVersion.getPatchVersion());
            return false;
        }

        if (oldMenuCells == null) {
            DebugTool.logError(TAG, "open sub menu called, but no Menu cells have been set");
            return false;
        }
        // We must see if we have a copy of this cell, since we clone the objects
        for (MenuCell clonedCell : oldMenuCells) {
            if (clonedCell.equals(cell) && clonedCell.getCellId() != MAX_ID) {
                // We've found the correct sub menu cell
                sendOpenSubMenu(clonedCell.getCellId());
                return true;
            }
        }
        return false;
    }

    private void sendOpenSubMenu(Integer id) {

        ShowAppMenu showAppMenu = new ShowAppMenu();
        showAppMenu.setMenuID(id);
        showAppMenu.setOnRPCResponseListener(new OnRPCResponseListener() {
            @Override
            public void onResponse(int correlationId, RPCResponse response) {
                if (response.getSuccess()) {
                    DebugTool.logInfo(TAG, "Open Sub Menu Request Successful");
                } else {
                    DebugTool.logError(TAG, "Open Sub Menu Request Failed");
                }
            }
        });

        internalInterface.sendRPC(showAppMenu);
    }

    // MENU CONFIG

    /**
     * This method is called via the screen manager to set the menuConfiguration.
     * This will be used when a menu item with sub-cells has a null value for menuConfiguration
     *
     * @param menuConfiguration - The default menuConfiguration
     */
    public void setMenuConfiguration(@NonNull final MenuConfiguration menuConfiguration) {

        if (sdlMsgVersion == null) {
            DebugTool.logError(TAG, "SDL Message Version is null. Cannot set Menu Configuration");
            return;
        }

        if (sdlMsgVersion.getMajorVersion() < 6) {
            DebugTool.logWarning(TAG, "Menu configurations is only supported on head units with RPC spec version 6.0.0 or later. Currently connected head unit RPC spec version is: " + sdlMsgVersion.getMajorVersion() + "." + sdlMsgVersion.getMinorVersion() + "." + sdlMsgVersion.getPatchVersion());
            return;
        }

        if (currentHMILevel == null || currentHMILevel.equals(HMILevel.HMI_NONE) || currentSystemContext.equals(SystemContext.SYSCTXT_MENU)) {
            // We are in NONE or the menu is in use, bail out of here
            DebugTool.logError(TAG, "Could not set main menu configuration, HMI level: " + currentHMILevel + ", required: 'Not-NONE', system context: " + currentSystemContext + ", required: 'Not MENU'");
            return;
        }

        // In the future, when the manager is switched to use queues, the menuConfiguration should be set when SetGlobalProperties response is received
        this.menuConfiguration = menuConfiguration;

        if (menuConfiguration.getMenuLayout() != null) {

            SetGlobalProperties setGlobalProperties = new SetGlobalProperties();
            setGlobalProperties.setMenuLayout(menuConfiguration.getMenuLayout());
            setGlobalProperties.setOnRPCResponseListener(new OnRPCResponseListener() {
                @Override
                public void onResponse(int correlationId, RPCResponse response) {
                    if (response.getSuccess()) {
                        DebugTool.logInfo(TAG, "Menu Configuration successfully set: " + menuConfiguration.toString());
                    } else {
                        DebugTool.logError(TAG, "onError: " + response.getResultCode() + " | Info: " + response.getInfo());
                    }
                }
            });
            internalInterface.sendRPC(setGlobalProperties);
        } else {
            DebugTool.logInfo(TAG, "Menu Layout is null, not sending setGlobalProperties");
        }
    }

    public MenuConfiguration getMenuConfiguration() {
        return this.menuConfiguration;
    }
    // UPDATING SYSTEM

    // ROOT MENU

    private void updateMenuAndDetermineBestUpdateMethod() {

        if (currentHMILevel == null || currentHMILevel.equals(HMILevel.HMI_NONE) || currentSystemContext.equals(SystemContext.SYSCTXT_MENU)) {
            // We are in NONE or the menu is in use, bail out of here
            DebugTool.logInfo(TAG, "HMI in None or System Context Menu, returning");
            waitingOnHMIUpdate = true;
            waitingUpdateMenuCells = menuCells;
            return;
        }

        if (inProgressUpdate != null && inProgressUpdate.size() > 0) {
            // there's an in-progress update so this needs to wait
            DebugTool.logInfo(TAG, "There is an in progress Menu Update, returning");
            hasQueuedUpdate = true;
            return;
        }

        // Checks against what the developer set for update mode and against the display type
        // to determine how the menu will be updated. This has the ability to be changed during
        // a session.
        if (checkUpdateMode(dynamicMenuUpdatesMode, displayType)) {
            // run the lists through the new algorithm
            RunScore rootScore = runMenuCompareAlgorithm(oldMenuCells, menuCells);
            if (rootScore == null) {
                // send initial menu (score will return null)
                // make a copy of our current cells
                DebugTool.logInfo(TAG, "Creating initial Menu");
                // Set the IDs if needed
                lastMenuId = menuCellIdMin;
                updateIdsOnMenuCells(menuCells, parentIdNotFound);
                this.oldMenuCells = new ArrayList<>(menuCells);
                createAndSendEntireMenu();
            } else {
                DebugTool.logInfo(TAG, "Dynamically Updating Menu");
                if (menuCells.size() == 0 && (oldMenuCells != null && oldMenuCells.size() > 0)) {
                    // the dev wants to clear the menu. We have old cells and an empty array of new ones.
                    deleteMenuWhenNewCellsEmpty();
                } else {
                    // lets dynamically update the root menu
                    dynamicallyUpdateRootMenu(rootScore);
                }
            }
        } else {
            // we are in compatibility mode
            DebugTool.logInfo(TAG, "Updating menus in compatibility mode");
            lastMenuId = menuCellIdMin;
            updateIdsOnMenuCells(menuCells, parentIdNotFound);
            // if the old cell array is not null, we want to delete the entire thing, else copy the new array
            if (oldMenuCells == null) {
                this.oldMenuCells = new ArrayList<>(menuCells);
            }
            createAndSendEntireMenu();
        }
    }

    private boolean checkUpdateMode(DynamicMenuUpdatesMode updateMode, String displayType) {

        if (updateMode.equals(DynamicMenuUpdatesMode.ON_WITH_COMPAT_MODE)) {
            if (displayType == null) {
                return true;
            }
            return (!displayType.equals(DisplayType.GEN3_8_INCH.toString()));

        } else if (updateMode.equals(DynamicMenuUpdatesMode.FORCE_OFF)) {
            return false;
        } else if (updateMode.equals(DynamicMenuUpdatesMode.FORCE_ON)) {
            return true;
        }

        return true;
    }

    private void deleteMenuWhenNewCellsEmpty() {
        sendDeleteRPCs(createDeleteRPCsForCells(oldMenuCells), new CompletionListener() {
            @Override
            public void onComplete(boolean success) {
                inProgressUpdate = null;

                if (!success) {
                    DebugTool.logError(TAG, "Error Sending Current Menu");
                } else {
                    DebugTool.logInfo(TAG, "Successfully Cleared Menu");
                }
                oldMenuCells = null;
                if (hasQueuedUpdate) {
                    hasQueuedUpdate = false;
                }
            }
        });
    }

    private void dynamicallyUpdateRootMenu(RunScore bestRootScore) {

        // we need to run through the keeps and see if they have subCells, as they also need to be run
        // through the compare function.
        List<Integer> newIntArray = bestRootScore.getCurrentMenu();
        List<Integer> oldIntArray = bestRootScore.getOldMenu();
        List<RPCRequest> deleteCommands;

        // Set up deletes
        List<MenuCell> deletes = new ArrayList<>();
        keepsOld = new ArrayList<>();
        for (int x = 0; x < oldIntArray.size(); x++) {
            Integer old = oldIntArray.get(x);
            if (old.equals(MARKED_FOR_DELETION)) {
                // grab cell to send to function to create delete commands
                deletes.add(oldMenuCells.get(x));
            } else if (old.equals(KEEP)) {
                keepsOld.add(oldMenuCells.get(x));
            }
        }
        // create the delete commands
        deleteCommands = createDeleteRPCsForCells(deletes);

        // Set up the adds
        List<MenuCell> adds = new ArrayList<>();
        keepsNew = new ArrayList<>();
        for (int x = 0; x < newIntArray.size(); x++) {
            Integer newInt = newIntArray.get(x);
            if (newInt.equals(MARKED_FOR_ADDITION)) {
                // grab cell to send to function to create add commands
                adds.add(menuCells.get(x));
            } else if (newInt.equals(KEEP)) {
                keepsNew.add(menuCells.get(x));
            }
        }
        updateIdsOnDynamicCells(adds);
        // this is needed for the onCommands to still work
        transferIdsToKeptCells(keepsNew);

        if (adds.size() > 0) {
            DebugTool.logInfo(TAG, "Sending root menu updates");
            sendDynamicRootMenuRPCs(deleteCommands, adds);
        } else {
            DebugTool.logInfo(TAG, "All root menu items are kept. Check the sub menus");
            runSubMenuCompareAlgorithm();
        }
    }

    // OTHER

    private void sendDynamicRootMenuRPCs(List<RPCRequest> deleteCommands, final List<MenuCell> updatedCells) {
        sendDeleteRPCs(deleteCommands, new CompletionListener() {
            @Override
            public void onComplete(boolean success) {
                createAndSendMenuCellRPCs(updatedCells, new CompletionListener() {
                    @Override
                    public void onComplete(boolean success) {
                        inProgressUpdate = null;

                        if (!success) {
                            DebugTool.logError(TAG, "Error Sending Current Menu");
                        }

                        if (hasQueuedUpdate) {
                            //setMenuCells(waitingUpdateMenuCells);
                            hasQueuedUpdate = false;
                        }
                    }
                });
            }
        });
    }

    // SUB MENUS

    // This is called in the listener in the sendMenu and sendSubMenuCommands Methods
    private void runSubMenuCompareAlgorithm() {
        // any cells that were re-added have their sub-cells added with them
        // at this point all we care about are the cells that were deemed equal and kept.
        if (keepsNew == null || keepsNew.size() == 0) {
            return;
        }

        List<SubCellCommandList> commandLists = new ArrayList<>();

        for (int i = 0; i < keepsNew.size(); i++) {

            MenuCell keptCell = keepsNew.get(i);
            MenuCell oldKeptCell = keepsOld.get(i);

            if (oldKeptCell.getSubCells() != null && oldKeptCell.getSubCells().size() > 0 && keptCell.getSubCells() != null && keptCell.getSubCells().size() > 0) {
                // ACTUAL LOGIC
                RunScore subScore = compareOldAndNewLists(oldKeptCell.getSubCells(), keptCell.getSubCells());

                if (subScore != null) {
                    DebugTool.logInfo(TAG, "Sub menu Run Score: " + oldKeptCell.getTitle() + " Score: " + subScore.getScore());
                    SubCellCommandList commandList = new SubCellCommandList(oldKeptCell.getTitle(), oldKeptCell.getCellId(), subScore, oldKeptCell.getSubCells(), keptCell.getSubCells());
                    commandLists.add(commandList);
                }
            }
        }
        createSubMenuDynamicCommands(commandLists);
    }

    private void createSubMenuDynamicCommands(final List<SubCellCommandList> commandLists) {

        // break out
        if (commandLists.size() == 0) {
            if (inProgressUpdate != null) {
                inProgressUpdate = null;
            }

            if (hasQueuedUpdate) {
                DebugTool.logInfo(TAG, "Menu Manager has waiting updates, sending now");
                setMenuCells(waitingUpdateMenuCells);
                hasQueuedUpdate = false;
            }
            DebugTool.logInfo(TAG, "All menu updates, including sub menus - done.");
            return;
        }

        final SubCellCommandList commandList = commandLists.remove(0);

        DebugTool.logInfo(TAG, "Creating and Sending Dynamic Sub Commands For Root Menu Cell: " + commandList.getMenuTitle());

        // grab the scores
        RunScore score = commandList.getListsScore();
        List<Integer> newIntArray = score.getCurrentMenu();
        List<Integer> oldIntArray = score.getOldMenu();

        // Grab the sub-menus from the parent cell
        final List<MenuCell> oldCells = commandList.getOldList();
        final List<MenuCell> newCells = commandList.getNewList();

        // Create the list for the adds
        List<MenuCell> subCellKeepsNew = new ArrayList<>();

        List<RPCRequest> deleteCommands;

        // Set up deletes
        List<MenuCell> deletes = new ArrayList<>();
        for (int x = 0; x < oldIntArray.size(); x++) {
            Integer old = oldIntArray.get(x);
            if (old.equals(MARKED_FOR_DELETION)) {
                // grab cell to send to function to create delete commands
                deletes.add(oldCells.get(x));
            }
        }
        // create the delete commands
        deleteCommands = createDeleteRPCsForCells(deletes);

        // Set up the adds
        List<MenuCell> adds = new ArrayList<>();
        for (int x = 0; x < newIntArray.size(); x++) {
            Integer newInt = newIntArray.get(x);
            if (newInt.equals(MARKED_FOR_ADDITION)) {
                // grab cell to send to function to create add commands
                adds.add(newCells.get(x));
            } else if (newInt.equals(KEEP)) {
                subCellKeepsNew.add(newCells.get(x));
            }
        }
        final List<MenuCell> addsWithNewIds = updateIdsOnDynamicSubCells(oldCells, adds, commandList.getParentId());
        // this is needed for the onCommands to still work
        transferIdsToKeptSubCells(oldCells, subCellKeepsNew);

        sendDeleteRPCs(deleteCommands, new CompletionListener() {
            @Override
            public void onComplete(boolean success) {
                if (addsWithNewIds != null && addsWithNewIds.size() > 0) {
                    createAndSendDynamicSubMenuRPCs(newCells, addsWithNewIds, new CompletionListener() {
                        @Override
                        public void onComplete(boolean success) {
                            // recurse through next sub list
                            DebugTool.logInfo(TAG, "Finished Sending Dynamic Sub Commands For Root Menu Cell: " + commandList.getMenuTitle());
                            createSubMenuDynamicCommands(commandLists);
                        }
                    });
                } else {
                    // no add commands to send, recurse through next sub list
                    DebugTool.logInfo(TAG, "Finished Sending Dynamic Sub Commands For Root Menu Cell: " + commandList.getMenuTitle());
                    createSubMenuDynamicCommands(commandLists);
                }
            }
        });
    }

    // OTHER HELPER METHODS:

    // COMPARISONS

    RunScore runMenuCompareAlgorithm(List<MenuCell> oldCells, List<MenuCell> newCells) {

        if (oldCells == null || oldCells.size() == 0) {
            return null;
        }

        RunScore bestScore = compareOldAndNewLists(oldCells, newCells);
        DebugTool.logInfo(TAG, "Best menu run score: " + bestScore.getScore());

        return bestScore;
    }

    private RunScore compareOldAndNewLists(List<MenuCell> oldCells, List<MenuCell> newCells) {

        RunScore bestRunScore = null;

        // This first loop is for each 'run'
        for (int run = 0; run < oldCells.size(); run++) {

            List<Integer> oldArray = new ArrayList<>(oldCells.size());
            List<Integer> newArray = new ArrayList<>(newCells.size());

            // Set the statuses
            setDeleteStatus(oldCells.size(), oldArray);
            setAddStatus(newCells.size(), newArray);

            int startIndex = 0;

            // Keep items that appear in both lists
            for (int oldItems = run; oldItems < oldCells.size(); oldItems++) {

                for (int newItems = startIndex; newItems < newCells.size(); newItems++) {

                    if (oldCells.get(oldItems).equals(newCells.get(newItems))) {
                        oldArray.set(oldItems, KEEP);
                        newArray.set(newItems, KEEP);
                        // set the new start index
                        startIndex = newItems + 1;
                        break;
                    }
                }
            }

            // Calculate number of adds, or the 'score' for this run
            int numberOfAdds = 0;

            for (int x = 0; x < newArray.size(); x++) {
                if (newArray.get(x).equals(MARKED_FOR_ADDITION)) {
                    numberOfAdds++;
                }
            }

            // see if we have a new best score and set it if we do
            if (bestRunScore == null || numberOfAdds < bestRunScore.getScore()) {
                bestRunScore = new RunScore(numberOfAdds, oldArray, newArray);
            }

        }
        return bestRunScore;
    }

    private void setDeleteStatus(Integer size, List<Integer> oldArray) {
        for (int i = 0; i < size; i++) {
            oldArray.add(MARKED_FOR_DELETION);
        }
    }

    private void setAddStatus(Integer size, List<Integer> newArray) {
        for (int i = 0; i < size; i++) {
            newArray.add(MARKED_FOR_ADDITION);
        }
    }

    // ARTWORKS

    /**
     * Get an array of artwork that needs to be uploaded from a list of menu cells
     *
     * @param cells List of MenuCell's to check
     * @return List of artwork that needs to be uploaded
     */
    private List<SdlArtwork> findAllArtworksToBeUploadedFromCells(List<MenuCell> cells) {
        // Make sure we can use images in the menus
        if (!hasImageFieldOfName(ImageFieldName.cmdIcon)) {
            return new ArrayList<>();
        }

        List<SdlArtwork> artworks = new ArrayList<>();
        for (MenuCell cell : cells) {
            if (fileManager.get() != null && fileManager.get().fileNeedsUpload(cell.getIcon())) {
                artworks.add(cell.getIcon());
            }

            if (cell.getSubCells() != null && cell.getSubCells().size() > 0 && hasImageFieldOfName(ImageFieldName.menuSubMenuSecondaryImage)) {
                if (fileManager.get() != null && fileManager.get().fileNeedsUpload(cell.getSecondaryArtwork())) {
                    artworks.add(cell.getSecondaryArtwork());
                }
            } else if ((cell.getSubCells() == null || cell.getSubCells().isEmpty()) && hasImageFieldOfName(ImageFieldName.menuCommandSecondaryImage)) {
                if (fileManager.get() != null && fileManager.get().fileNeedsUpload(cell.getSecondaryArtwork())) {
                    artworks.add(cell.getSecondaryArtwork());
                }
            }

            if (cell.getSubCells() != null && cell.getSubCells().size() > 0) {
                artworks.addAll(findAllArtworksToBeUploadedFromCells(cell.getSubCells()));
            }
        }

        return artworks;
    }

    /**
     * Determine if cells should or should not be uploaded to the head unit with artworks.
     *
     * No artworks will be uploaded if:
     *
     * 1. If any cell has a dynamic artwork that is not uploaded
     * 2. If any cell contains a secondary artwork may be used on the head unit, and the cell has a dynamic secondary artwork that is not uploaded
     * 3. If any cell's subcells fail check (1) or (2)
     *
     * @param cells List of MenuCell's to check
     * @return True if the cells should be uploaded with artwork, false if they should not
     */
    private boolean shouldRPCsIncludeImages(List<MenuCell> cells) {
        for (MenuCell cell : cells) {
            SdlArtwork artwork = cell.getIcon();
            SdlArtwork secondaryArtwork = cell.getSecondaryArtwork();
            if (artwork != null && !artwork.isStaticIcon() && fileManager.get() != null && !fileManager.get().hasUploadedFile(artwork)) {
                return false;
            } else if (cell.getSubCells() != null && cell.getSubCells().size() > 0 && hasImageFieldOfName(ImageFieldName.menuSubMenuSecondaryImage)) {
                if (secondaryArtwork != null && !secondaryArtwork.isStaticIcon() && fileManager.get() != null && !fileManager.get().hasUploadedFile(secondaryArtwork)) {
                    return false;
                }
            } else if ((cell.getSubCells() == null || cell.getSubCells().isEmpty()) && hasImageFieldOfName(ImageFieldName.menuCommandSecondaryImage)) {
                if (secondaryArtwork != null && !secondaryArtwork.isStaticIcon() && fileManager.get() != null && !fileManager.get().hasUploadedFile(secondaryArtwork)) {
                    return false;
                }
            } else if (cell.getSubCells() != null && cell.getSubCells().size() > 0 && !shouldRPCsIncludeImages(cell.getSubCells())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasImageFieldOfName(ImageFieldName imageFieldName) {
        return defaultMainWindowCapability == null || ManagerUtility.WindowCapabilityUtility.hasImageFieldOfName(defaultMainWindowCapability, imageFieldName);
    }

    private boolean hasTextFieldOfName(TextFieldName textFieldName) {
        return defaultMainWindowCapability == null || ManagerUtility.WindowCapabilityUtility.hasTextFieldOfName(defaultMainWindowCapability, textFieldName);
    }

    // IDs

    private void updateIdsOnDynamicCells(List<MenuCell> dynamicCells) {
        if (menuCells != null && menuCells.size() > 0 && dynamicCells != null && dynamicCells.size() > 0) {
            for (int z = 0; z < menuCells.size(); z++) {
                MenuCell mainCell = menuCells.get(z);
                for (int i = 0; i < dynamicCells.size(); i++) {
                    MenuCell dynamicCell = dynamicCells.get(i);
                    if (mainCell.equals(dynamicCell)) {
                        int newId = ++lastMenuId;
                        menuCells.get(z).setCellId(newId);
                        dynamicCells.get(i).setCellId(newId);

                        if (mainCell.getSubCells() != null && mainCell.getSubCells().size() > 0) {
                            updateIdsOnMenuCells(mainCell.getSubCells(), mainCell.getCellId());
                        }
                        break;
                    }
                }
            }
        }
    }

    private List<MenuCell> updateIdsOnDynamicSubCells(List<MenuCell> oldList, List<MenuCell> dynamicCells, Integer parentId) {
        if (oldList != null && oldList.size() > 0 && dynamicCells != null && dynamicCells.size() > 0) {
            for (int z = 0; z < oldList.size(); z++) {
                MenuCell mainCell = oldList.get(z);
                for (int i = 0; i < dynamicCells.size(); i++) {
                    MenuCell dynamicCell = dynamicCells.get(i);
                    if (mainCell.equals(dynamicCell)) {
                        int newId = ++lastMenuId;
                        oldList.get(z).setCellId(newId);
                        dynamicCells.get(i).setParentCellId(parentId);
                        dynamicCells.get(i).setCellId(newId);
                    } else {
                        int newId = ++lastMenuId;
                        dynamicCells.get(i).setParentCellId(parentId);
                        dynamicCells.get(i).setCellId(newId);
                    }
                }
            }
            return dynamicCells;
        }
        return null;
    }


    /**
     * Assign cell ids on an array of menu cells given a parent id (or no parent id)
     *
     * @param cells    List of menu cells to update
     * @param parentId The parent id to assign if needed
     */
    private void updateIdsOnMenuCells(List<MenuCell> cells, int parentId) {
        for (MenuCell cell : cells) {
            int newId = ++lastMenuId;
            cell.setCellId(newId);
            cell.setParentCellId(parentId);
            if (cell.getSubCells() != null && cell.getSubCells().size() > 0) {
                updateIdsOnMenuCells(cell.getSubCells(), cell.getCellId());
            }
        }
    }

    private void transferIdsToKeptCells(List<MenuCell> keeps) {
        for (int z = 0; z < oldMenuCells.size(); z++) {
            MenuCell oldCell = oldMenuCells.get(z);
            for (int i = 0; i < keeps.size(); i++) {
                MenuCell keptCell = keeps.get(i);
                if (oldCell.equals(keptCell)) {
                    keptCell.setCellId(oldCell.getCellId());
                    break;
                }
            }
        }
    }

    private void transferIdsToKeptSubCells(List<MenuCell> old, List<MenuCell> keeps) {
        for (int z = 0; z < old.size(); z++) {
            MenuCell oldCell = old.get(z);
            for (int i = 0; i < keeps.size(); i++) {
                MenuCell keptCell = keeps.get(i);
                if (oldCell.equals(keptCell)) {
                    keptCell.setCellId(oldCell.getCellId());
                    break;
                }
            }
        }
    }

    // DELETES

    /**
     * Create an array of DeleteCommand and DeleteSubMenu RPCs from an ArrayList of menu cells
     *
     * @param cells List of menu cells to use
     * @return List of DeleteCommand and DeletedSubmenu RPCs
     */
    private List<RPCRequest> createDeleteRPCsForCells(List<MenuCell> cells) {
        List<RPCRequest> deletes = new ArrayList<>();
        for (MenuCell cell : cells) {
            if (cell.getSubCells() == null) {
                DeleteCommand delete = new DeleteCommand(cell.getCellId());
                deletes.add(delete);
            } else {
                DeleteSubMenu delete = new DeleteSubMenu(cell.getCellId());
                deletes.add(delete);
            }
        }
        return deletes;
    }

    // COMMANDS / SUBMENU RPCs

    /**
     * This method will receive the cells to be added. It will then build an array of add commands using the correct index to position the new items in the correct location.
     * e.g. If the new menu array is [A, B, C, D] but only [C, D] are new we need to pass [A, B , C , D] so C and D can be added to index 2 and 3 respectively.
     *
     * @param cellsToAdd        List of MenuCell's that will be added to the menu, this array must contain only cells that are not already in the menu.
     * @param shouldHaveArtwork Boolean that indicates whether artwork should be applied to the RPC or not
     * @return List of RPCRequest addCommands
     */
    private List<RPCRequest> mainMenuCommandsForCells(List<MenuCell> cellsToAdd, boolean shouldHaveArtwork) {
        List<RPCRequest> builtCommands = new ArrayList<>();

        // We need the index so we will use this type of loop
        for (int z = 0; z < menuCells.size(); z++) {
            MenuCell mainCell = menuCells.get(z);
            for (int i = 0; i < cellsToAdd.size(); i++) {
                MenuCell addCell = cellsToAdd.get(i);
                if (mainCell.equals(addCell)) {
                    if (addCell.getSubCells() != null && addCell.getSubCells().size() > 0) {
                        builtCommands.add(subMenuCommandForMenuCell(addCell, shouldHaveArtwork, z));
                    } else {
                        builtCommands.add(commandForMenuCell(addCell, shouldHaveArtwork, z));
                    }
                    break;
                }
            }
        }
        return builtCommands;
    }

    /**
     * Creates AddSubMenu RPCs for the passed array of menu cells, AND all of those cells' subcell RPCs, both AddCommands and AddSubMenus
     *
     * @param cells             List of MenuCell's to create RPCs for
     * @param shouldHaveArtwork Boolean that indicates whether artwork should be applied to the RPC or not
     * @return An List of RPCs of AddSubMenus and their associated subcell RPCs
     */
    private List<RPCRequest> subMenuCommandsForCells(List<MenuCell> cells, boolean shouldHaveArtwork) {
        List<RPCRequest> builtCommands = new ArrayList<>();
        for (MenuCell cell : cells) {
            if (cell.getSubCells() != null && cell.getSubCells().size() > 0) {
                builtCommands.addAll(allCommandsForCells(cell.getSubCells(), shouldHaveArtwork));
            }
        }
        return builtCommands;
    }

    /**
     * Creates AddCommand and AddSubMenu RPCs for a passed array of cells, AND all of those cells' subcell RPCs, both AddCommands and AddSubmenus
     *
     * @param cells             List of MenuCell's to create RPCs for
     * @param shouldHaveArtwork Boolean that indicates whether artwork should be applied to the RPC or not
     * @return An List of RPCs of AddCommand and AddSubMenus for the array of menu cells and their subcells, recursively
     */
    List<RPCRequest> allCommandsForCells(List<MenuCell> cells, boolean shouldHaveArtwork) {
        List<RPCRequest> builtCommands = new ArrayList<>();

        // We need the index so we will use this type of loop
        for (int i = 0; i < cells.size(); i++) {
            MenuCell cell = cells.get(i);
            if (cell.getSubCells() != null && cell.getSubCells().size() > 0) {
                builtCommands.add(subMenuCommandForMenuCell(cell, shouldHaveArtwork, i));
                // recursively grab the commands for all the sub cells
                builtCommands.addAll(allCommandsForCells(cell.getSubCells(), shouldHaveArtwork));
            } else {
                builtCommands.add(commandForMenuCell(cell, shouldHaveArtwork, i));
            }
        }
        return builtCommands;
    }

    private List<RPCRequest> createCommandsForDynamicSubCells(List<MenuCell> oldMenuCells, List<MenuCell> cells, boolean shouldHaveArtwork) {
        List<RPCRequest> builtCommands = new ArrayList<>();
        for (int z = 0; z < oldMenuCells.size(); z++) {
            MenuCell oldCell = oldMenuCells.get(z);
            for (int i = 0; i < cells.size(); i++) {
                MenuCell cell = cells.get(i);
                if (cell.equals(oldCell)) {
                    builtCommands.add(commandForMenuCell(cell, shouldHaveArtwork, z));
                    break;
                }
            }
        }
        return builtCommands;
    }

    /**
     * An individual AddCommand RPC for a given MenuCell
     *
     * @param cell              MenuCell to create RPCs for
     * @param shouldHaveArtwork Boolean that indicates whether artwork should be applied to the RPC or not
     * @param position          The position the AddCommand RPC should be given
     * @return The AddCommand RPC
     */
    private AddCommand commandForMenuCell(MenuCell cell, boolean shouldHaveArtwork, int position) {

        MenuParams params = new MenuParams(cell.getUniqueTitle());
        params.setSecondaryText((cell.getSecondaryText() != null && cell.getSecondaryText().length() > 0 && hasTextFieldOfName(TextFieldName.menuCommandSecondaryText)) ? cell.getSecondaryText() : null);
        params.setTertiaryText((cell.getTertiaryText() != null && cell.getTertiaryText().length() > 0 && hasTextFieldOfName(TextFieldName.menuCommandTertiaryText)) ? cell.getTertiaryText() : null);
        params.setParentID(cell.getParentCellId() != MAX_ID ? cell.getParentCellId() : null);
        params.setPosition(position);

        AddCommand command = new AddCommand(cell.getCellId());
        command.setMenuParams(params);
        if (cell.getVoiceCommands() != null && !cell.getVoiceCommands().isEmpty()) {
            command.setVrCommands(cell.getVoiceCommands());
        } else {
            command.setVrCommands(null);
        }
        command.setCmdIcon((cell.getIcon() != null && shouldHaveArtwork) ? cell.getIcon().getImageRPC() : null);
        command.setSecondaryImage((cell.getSecondaryArtwork() != null && shouldHaveArtwork && !(fileManager.get() != null && fileManager.get().fileNeedsUpload(cell.getSecondaryArtwork()))) ? cell.getSecondaryArtwork().getImageRPC() : null);

        return command;
    }

    /**
     * An individual AddSubMenu RPC for a given MenuCell
     *
     * @param cell              The cell to create the RPC for
     * @param shouldHaveArtwork Whether artwork should be applied to the RPC
     * @param position          The position the AddSubMenu RPC should be given
     * @return The AddSubMenu RPC
     */
    private AddSubMenu subMenuCommandForMenuCell(MenuCell cell, boolean shouldHaveArtwork, int position) {
        AddSubMenu subMenu = new AddSubMenu(cell.getCellId(), cell.getUniqueTitle());
        subMenu.setSecondaryText((cell.getSecondaryText() != null && cell.getSecondaryText().length() > 0 && hasTextFieldOfName(TextFieldName.menuSubMenuSecondaryText)) ? cell.getSecondaryText() : null);
        subMenu.setTertiaryText((cell.getTertiaryText() != null && cell.getTertiaryText().length() > 0 && hasTextFieldOfName(TextFieldName.menuSubMenuTertiaryText)) ? cell.getTertiaryText() : null);
        subMenu.setPosition(position);
        if (cell.getSubMenuLayout() != null) {
            subMenu.setMenuLayout(cell.getSubMenuLayout());
        } else if (menuConfiguration != null && menuConfiguration.getSubMenuLayout() != null) {
            subMenu.setMenuLayout(menuConfiguration.getSubMenuLayout());
        }
        subMenu.setMenuIcon((shouldHaveArtwork && (cell.getIcon() != null && cell.getIcon().getImageRPC() != null)) ? cell.getIcon().getImageRPC() : null);
        subMenu.setSecondaryImage((shouldHaveArtwork && !(fileManager.get() != null && fileManager.get().fileNeedsUpload(cell.getSecondaryArtwork())) && (cell.getSecondaryArtwork() != null && cell.getSecondaryArtwork().getImageRPC() != null)) ? cell.getSecondaryArtwork().getImageRPC() : null);
        return subMenu;
    }

    // CELL COMMAND HANDLING


    /**
     * Call a listener for a currently displayed MenuCell based on the incoming OnCommand notification
     *
     * @param cells   List of MenuCell's to check (including their subcells)
     * @param command OnCommand notification retrieved
     * @return True if the handler was found, false if it was not found
     */
    private boolean callListenerForCells(List<MenuCell> cells, OnCommand command) {
        if (cells != null && cells.size() > 0 && command != null) {
            for (MenuCell cell : cells) {

                if (cell.getCellId() == command.getCmdID() && cell.getMenuSelectionListener() != null) {
                    cell.getMenuSelectionListener().onTriggered(command.getTriggerSource());
                    return true;
                }
                if (cell.getSubCells() != null && cell.getSubCells().size() > 0) {
                    // for each cell, if it has sub cells, recursively loop through those as well
                    if (callListenerForCells(cell.getSubCells(), command)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // LISTENERS

    private void addListeners() {
        // DISPLAY CAPABILITIES - via SCM
        onDisplaysCapabilityListener = new OnSystemCapabilityListener() {
            @Override
            public void onCapabilityRetrieved(Object capability) {
                // instead of using the parameter it's more safe to use the convenience method
                List<DisplayCapability> capabilities = SystemCapabilityManager.convertToList(capability, DisplayCapability.class);
                if (capabilities == null || capabilities.size() == 0) {
                    DebugTool.logError(TAG, "SoftButton Manager - Capabilities sent here are null or empty");
                } else {
                    DisplayCapability display = capabilities.get(0);
                    displayType = display.getDisplayName();
                    for (WindowCapability windowCapability : display.getWindowCapabilities()) {
                        int currentWindowID = windowCapability.getWindowID() != null ? windowCapability.getWindowID() : PredefinedWindows.DEFAULT_WINDOW.getValue();
                        if (currentWindowID == PredefinedWindows.DEFAULT_WINDOW.getValue()) {
                            defaultMainWindowCapability = windowCapability;
                        }
                    }
                }
            }

            @Override
            public void onError(String info) {
                DebugTool.logError(TAG, "Display Capability cannot be retrieved");
                defaultMainWindowCapability = null;
            }
        };
        if (internalInterface.getSystemCapabilityManager() != null) {
            this.internalInterface.getSystemCapabilityManager().addOnSystemCapabilityListener(SystemCapabilityType.DISPLAYS, onDisplaysCapabilityListener);
        }

        // HMI UPDATES
        hmiListener = new OnRPCNotificationListener() {
            @Override
            public void onNotified(RPCNotification notification) {
                OnHMIStatus onHMIStatus = (OnHMIStatus) notification;
                if (onHMIStatus.getWindowID() != null && onHMIStatus.getWindowID() != PredefinedWindows.DEFAULT_WINDOW.getValue()) {
                    return;
                }
                HMILevel oldHMILevel = currentHMILevel;
                currentHMILevel = onHMIStatus.getHmiLevel();

                // Auto-send an updated menu if we were in NONE and now we are not, and we need an update
                if (oldHMILevel == HMILevel.HMI_NONE && currentHMILevel != HMILevel.HMI_NONE && currentSystemContext != SystemContext.SYSCTXT_MENU) {
                    if (waitingOnHMIUpdate) {
                        DebugTool.logInfo(TAG, "We now have proper HMI, sending waiting update");
                        setMenuCells(waitingUpdateMenuCells);
                        waitingUpdateMenuCells.clear();
                        return;
                    }
                }

                // If we don't check for this and only update when not in the menu, there can be IN_USE errors, especially with submenus.
                // We also don't want to encourage changing out the menu while the user is using it for usability reasons.
                SystemContext oldContext = currentSystemContext;
                currentSystemContext = onHMIStatus.getSystemContext();

                if (oldContext == SystemContext.SYSCTXT_MENU && currentSystemContext != SystemContext.SYSCTXT_MENU && currentHMILevel != HMILevel.HMI_NONE) {
                    if (waitingOnHMIUpdate) {
                        DebugTool.logInfo(TAG, "We now have a proper system context, sending waiting update");
                        setMenuCells(waitingUpdateMenuCells);
                        waitingUpdateMenuCells.clear();
                    }
                }
            }
        };
        internalInterface.addOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, hmiListener);

        // COMMANDS
        commandListener = new OnRPCNotificationListener() {
            @Override
            public void onNotified(RPCNotification notification) {
                OnCommand onCommand = (OnCommand) notification;
                callListenerForCells(menuCells, onCommand);
            }
        };
        internalInterface.addOnRPCNotificationListener(FunctionID.ON_COMMAND, commandListener);
    }

    // SEND NEW MENU ITEMS

    private void createAndSendEntireMenu() {

        if (currentHMILevel == null || currentHMILevel.equals(HMILevel.HMI_NONE) || currentSystemContext.equals(SystemContext.SYSCTXT_MENU)) {
            // We are in NONE or the menu is in use, bail out of here
            DebugTool.logInfo(TAG, "HMI in None or System Context Menu, returning");
            waitingOnHMIUpdate = true;
            waitingUpdateMenuCells = menuCells;
            return;
        }

        if (inProgressUpdate != null && inProgressUpdate.size() > 0) {
            // there's an in-progress update so this needs to wait
            DebugTool.logInfo(TAG, "There is an in progress Menu Update, returning");
            hasQueuedUpdate = true;
            return;
        }

        deleteRootMenu(new CompletionListener() {
            @Override
            public void onComplete(boolean success) {
                createAndSendMenuCellRPCs(menuCells, new CompletionListener() {
                    @Override
                    public void onComplete(boolean success) {
                        inProgressUpdate = null;

                        if (!success) {
                            DebugTool.logError(TAG, "Error Sending Current Menu");
                        }

                        if (hasQueuedUpdate) {
                            setMenuCells(waitingUpdateMenuCells);
                            hasQueuedUpdate = false;
                        }
                    }
                });
            }
        });
    }

    private void createAndSendMenuCellRPCs(final List<MenuCell> menu, final CompletionListener listener) {

        if (menu.size() == 0) {
            if (listener != null) {
                // This can be considered a success if the user was clearing out their menu
                listener.onComplete(true);
            }
            return;
        }

        List<RPCRequest> mainMenuCommands;
        final List<RPCRequest> subMenuCommands;

        if (!shouldRPCsIncludeImages(menu) || !hasImageFieldOfName(ImageFieldName.cmdIcon)) {
            // Send artwork-less menu
            mainMenuCommands = mainMenuCommandsForCells(menu, false);
            subMenuCommands = subMenuCommandsForCells(menu, false);
        } else {
            mainMenuCommands = mainMenuCommandsForCells(menu, true);
            subMenuCommands = subMenuCommandsForCells(menu, true);
        }

        // add all built commands to inProgressUpdate
        inProgressUpdate = new ArrayList<>(mainMenuCommands);
        inProgressUpdate.addAll(subMenuCommands);

        internalInterface.sendSequentialRPCs(mainMenuCommands, new OnMultipleRequestListener() {
            @Override
            public void onUpdate(int remainingRequests) {
                // nothing here
            }

            @Override
            public void onFinished() {

                if (subMenuCommands.size() > 0) {
                    sendSubMenuCommandRPCs(subMenuCommands, listener);
                    DebugTool.logInfo(TAG, "Finished sending main menu commands. Sending sub menu commands.");
                } else {

                    if (keepsNew != null && keepsNew.size() > 0) {
                        runSubMenuCompareAlgorithm();
                    } else {
                        inProgressUpdate = null;
                        DebugTool.logInfo(TAG, "Finished sending main menu commands.");
                    }
                }
            }

            @Override
            public void onResponse(int correlationId, RPCResponse response) {
                if (response.getSuccess()) {
                    try {
                        DebugTool.logInfo(TAG, "Main Menu response: " + response.serializeJSON().toString());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else {
                    DebugTool.logError(TAG, "Result: " + response.getResultCode() + " Info: " + response.getInfo());
                }
            }
        });
    }

    private void sendSubMenuCommandRPCs(List<RPCRequest> commands, final CompletionListener listener) {

        internalInterface.sendSequentialRPCs(commands, new OnMultipleRequestListener() {
            @Override
            public void onUpdate(int remainingRequests) {

            }

            @Override
            public void onFinished() {

                if (keepsNew != null && keepsNew.size() > 0) {
                    runSubMenuCompareAlgorithm();
                } else {
                    DebugTool.logInfo(TAG, "Finished Updating Menu");
                    inProgressUpdate = null;

                    if (listener != null) {
                        listener.onComplete(true);
                    }
                }
            }

            @Override
            public void onResponse(int correlationId, RPCResponse response) {
                if (response.getSuccess()) {
                    try {
                        DebugTool.logInfo(TAG, "Sub Menu response: " + response.serializeJSON().toString());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else {
                    DebugTool.logError(TAG, "Failed to send sub menu commands: " + response.getInfo());
                    if (listener != null) {
                        listener.onComplete(false);
                    }
                }
            }
        });
    }

    private void createAndSendDynamicSubMenuRPCs(List<MenuCell> newMenu, final List<MenuCell> adds, final CompletionListener listener) {

        if (adds.size() == 0) {
            if (listener != null) {
                // This can be considered a success if the user was clearing out their menu
                DebugTool.logError(TAG, "Called createAndSendDynamicSubMenuRPCs with empty menu");
                listener.onComplete(true);
            }
            return;
        }

        List<RPCRequest> mainMenuCommands;

        if (!shouldRPCsIncludeImages(adds) || !hasImageFieldOfName(ImageFieldName.cmdIcon)) {
            // Send artwork-less menu
            mainMenuCommands = createCommandsForDynamicSubCells(newMenu, adds, false);
        } else {
            mainMenuCommands = createCommandsForDynamicSubCells(newMenu, adds, true);
        }

        internalInterface.sendSequentialRPCs(mainMenuCommands, new OnMultipleRequestListener() {
            @Override
            public void onUpdate(int remainingRequests) {
                // nothing here
            }

            @Override
            public void onFinished() {

                if (listener != null) {
                    listener.onComplete(true);
                }
            }

            @Override
            public void onResponse(int correlationId, RPCResponse response) {
                if (response.getSuccess()) {
                    try {
                        DebugTool.logInfo(TAG, "Dynamic Sub Menu response: " + response.serializeJSON().toString());
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                } else {
                    DebugTool.logError(TAG, "Result: " + response.getResultCode() + " Info: " + response.getInfo());
                }
            }
        });
    }

    // DELETE OLD MENU ITEMS

    private void deleteRootMenu(final CompletionListener listener) {

        if (oldMenuCells == null || oldMenuCells.size() == 0) {
            if (listener != null) {
                // technically this method is successful if there's nothing to delete
                DebugTool.logInfo(TAG, "No old cells to delete, returning");
                listener.onComplete(true);
            }
        } else {
            sendDeleteRPCs(createDeleteRPCsForCells(oldMenuCells), listener);
        }
    }

    private void sendDeleteRPCs(List<RPCRequest> deleteCommands, final CompletionListener listener) {
        if (oldMenuCells != null && oldMenuCells.size() == 0) {
            if (listener != null) {
                // technically this method is successful if there's nothing to delete
                DebugTool.logInfo(TAG, "No old cells to delete, returning");
                listener.onComplete(true);
            }
            return;
        }

        if (deleteCommands == null || deleteCommands.size() == 0) {
            // no dynamic deletes required. return
            if (listener != null) {
                // technically this method is successful if there's nothing to delete
                listener.onComplete(true);
            }
            return;
        }

        internalInterface.sendRPCs(deleteCommands, new OnMultipleRequestListener() {
            @Override
            public void onUpdate(int remainingRequests) {

            }

            @Override
            public void onFinished() {
                DebugTool.logInfo(TAG, "Successfully deleted cells");
                if (listener != null) {
                    listener.onComplete(true);
                }
            }

            @Override
            public void onResponse(int correlationId, RPCResponse response) {

            }
        });
    }

    private List<MenuCell> cloneMenuCellsList(List<MenuCell> originalList) {
        if (originalList == null) {
            return null;
        }

        List<MenuCell> clone = new ArrayList<>();
        for (MenuCell menuCell : originalList) {
            clone.add(menuCell.clone());
        }
        return clone;
    }

    private void addUniqueNamesToCells(List<MenuCell> cells) {
        HashMap<String, Integer> dictCounter = new HashMap<>();

        for (MenuCell cell : cells) {
            String cellName = cell.getTitle();
            Integer counter = dictCounter.get(cellName);

            if (counter != null) {
                dictCounter.put(cellName, ++counter);
                cell.setUniqueTitle(cellName + " (" + counter + ")");
            } else {
                dictCounter.put(cellName, 1);
            }

            if (cell.getSubCells() != null && cell.getSubCells().size() > 0) {
                addUniqueNamesToCells(cell.getSubCells());
            }
        }
    }


    /**
     * Check for cell lists with completely duplicate information, or any duplicate voiceCommands
     *
     * @param cells            List of MenuCell's you will be adding
     * @param allVoiceCommands List of String's for VoiceCommands (Used for recursive calls to check voiceCommands of the cells)
     * @return Boolean that indicates whether menuCells are unique or not
     */
    private boolean menuCellsAreUnique(List<MenuCell> cells, ArrayList<String> allVoiceCommands) {
        //Check all voice commands for identical items and check each list of cells for identical cells
        HashSet<MenuCell> identicalCellsCheckSet = new HashSet<>();

        for (MenuCell cell : cells) {
            identicalCellsCheckSet.add(cell);

            // Recursively check the subcell lists to see if they are all unique as well. If anything is not, this will chain back up the list to return false.
            if (cell.getSubCells() != null && cell.getSubCells().size() > 0) {
                boolean subCellsAreUnique = menuCellsAreUnique(cell.getSubCells(), allVoiceCommands);

                if (!subCellsAreUnique) {
                    DebugTool.logError(TAG, "Not all subCells are unique. The menu will not be set.");
                    return false;
                }
            }

            // Voice commands have to be identical across all lists
            if (cell.getVoiceCommands() == null) {
                continue;
            }
            allVoiceCommands.addAll(cell.getVoiceCommands());
        }


        // Check for duplicate cells
        if (identicalCellsCheckSet.size() != cells.size()) {
            DebugTool.logError(TAG, "Not all cells are unique. The menu will not be set.");
            return false;
        }

        // All the VR commands must be unique
        HashSet<String> voiceCommandsSet = new HashSet<>(allVoiceCommands);
        if (allVoiceCommands.size() != voiceCommandsSet.size()) {
            DebugTool.logError(TAG, "Attempted to create a menu with duplicate voice commands. Voice commands must be unique. The menu will not be set");
            return false;
        }

        return true;
    }
}
