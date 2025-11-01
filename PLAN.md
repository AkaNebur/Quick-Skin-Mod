Of course! Refactoring a large UI screen class is an excellent way to improve maintainability and readability. Here is a very detailed plan to break down the `PlayerSkinMenuScreen` into smaller, more manageable components.

### Overall Goal

The primary goal is to apply the **Single Responsibility Principle** to the `PlayerSkinMenuScreen`. Currently, it acts as a "god object" responsible for:
1.  Overall screen state management (GUI scale, closing state).
2.  Calculating the layout and dimensions for all UI elements.
3.  Creating and managing every individual widget (buttons, lists, player preview).
4.  Handling business logic for events like skin selection, file import, and model type changes.

We will refactor this into a "container" or "orchestrator" pattern. The `PlayerSkinMenuScreen` will become a simple container that holds and positions larger, self-contained panels. Each panel will be responsible for its own internal layout, state, and widgets.

---

### Proposed New File Structure

We will introduce a new package `panel` inside `gui` to hold these new composite widgets.

```
.../gui/
  ├── screen/
  │   └── PlayerSkinMenuScreen.java  (Will be refactored)
  ├── panel/
  │   ├── ActionButtonsPanel.java    (New)
  │   ├── LinkButtonsPanel.java      (New)
  │   ├── PlayerPreviewPanel.java    (New)
  │   └── SkinListPanel.java         (New)
  ├── util/
  │   └── ...
  └── widget/
      └── ...
```

---

### Refactoring Plan Details

#### 1. `PlayerSkinMenuScreen.java` (The Refactored "Orchestrator")

The main screen will be heavily simplified. Its new responsibilities will be:

*   **Be the Main Container:** It will hold the root panel and manage the overall screen lifecycle (`init`, `render`, `onClose`).
*   **GUI Scale Management:** It will continue to manage forcing and restoring the GUI scale, as this is a screen-level concern.
*   **Orchestrate Communication:** It will act as a mediator between the panels. For example, when a skin is selected in the `SkinListPanel`, the screen will receive this event and tell the `PlayerPreviewPanel` to update its display.
*   **Handle Global Events:** It will still handle file drops (`onFilesDrop`) and top-level key presses (`keyPressed`).

**Structural Changes:**

*   **Fields:** Remove all individual `Button` and `Widget` fields (`autoModelButton`, `importButton`, `playerWidget`, etc.). Replace them with fields for the new panels:
    ```java
    private SkinListPanel skinListPanel;
    private PlayerPreviewPanel playerPreviewPanel;
    private ActionButtonsPanel actionButtonsPanel;
    private LinkButtonsPanel linkButtonsPanel;
    ```
*   **`init()` method:** This will be the most significant change. It will be reduced to:
    1.  Performing GUI scale checks.
    2.  Calculating the main panel's overall dimensions (`panelX`, `panelY`, `panelWidth`, `panelHeight`).
    3.  Calculating the bounds for each of the major panels (e.g., left panel width, right panel width, bottom action bar height).
    4.  Instantiating the new panel classes, passing them their bounds (`x`, `y`, `width`, `height`) and any necessary callbacks.
    5.  Adding the panels to the screen's renderables (`addRenderableWidget`).
*   **Logic Methods:**
    *   `onSkinSelected(SkinEntry entry)`: Will now be a callback passed to the `SkinListPanel`. When triggered, it will call `playerPreviewPanel.updateSkin(entry.getMetadata())`.
    *   `openImportDialog()`, `handleSkinImport()`: Logic will remain, but the `importButton` will trigger it via a callback passed to the `ActionButtonsPanel`.
    *   `refreshSkinList()`: Will simply call `skinListPanel.refresh()`.

---

#### 2. `SkinListPanel.java` (New Class)

This panel will manage the left side of the screen.

*   **Responsibilities:**
    *   Contain the `SkinListWidget`.
    *   Potentially contain a future search bar or sorting buttons above the list.
    *   Load the initial list of skins from `LocalAssetManager`.
    *   Handle the selection logic and expose an event/callback for when a skin is selected.
*   **Structure:**
    *   It will extend `AbstractWidget` or a similar base class to act as a container.
    *   **Fields:**
        ```java
        private SkinListWidget skinListWidget;
        // private Consumer<SkinEntry> onSkinSelectedCallback;
        ```
    *   **Constructor:** Will take its position/dimensions and the `onSkinSelected` callback.
    *   **`init()` or Constructor Logic:** Will create the `SkinListWidget`, position it within the panel's bounds, and load the skins.
    *   **Public Methods:**
        *   `refresh()`: To clear and reload the skin list.
        *   `setSelected(AssetMetadata metadata)`: To programmatically select a skin (useful after an import).

---

#### 3. `PlayerPreviewPanel.java` (New Class)

This panel will manage the right side of the screen, focusing on the 3D player model.

*   **Responsibilities:**
    *   Contain and manage the `PlayerWidget`.
    *   Contain and manage the model type buttons (`auto`, `classic`, `slim`) and the `RotateButton`.
    *   Handle the layout of the player preview and its associated buttons. The logic to position the `PlayerWidget` relative to the model buttons will move here.
    *   Manage the `currentModelType` state internally.
    *   Update the `PlayerWidget`'s model and skin when instructed by the main screen.
*   **Structure:**
    *   Extend `AbstractWidget`.
    *   **Fields:**
        ```java
        private PlayerWidget playerWidget;
        private Button autoModelButton, classicModelButton, slimModelButton;
        private RotateButton rotateButton;
        private String currentModelType;
        ```
    *   **Constructor:** Will take its position/dimensions.
    *   **`init()` or Constructor Logic:** Will create all its child widgets and lay them out within its bounds. The complex positioning logic from the original `init` method moves here.
    *   **Public Methods:**
        *   `updateSkin(AssetMetadata metadata)`: Sets the skin on the `PlayerWidget` and updates the model type if set to "auto".
        *   `updateCape(AssetMetadata metadata)`: For when cape selection is added.
    *   **Private Methods:**
        *   `setModelType(String modelType)`: The internal logic for handling model button clicks.
        *   `updateModelButtonStates()`: The logic to enable/disable the model buttons based on selection.

---

#### 4. `ActionButtonsPanel.java` (New Class)

This panel will manage the rows of buttons at the bottom of the screen.

*   **Responsibilities:**
    *   Contain `importButton`, `hdSkinWebsiteButton`, `skinWebsiteButton`, `capeButton`, and `doneButton`.
    *   Handle the complex layout of these buttons (the 4-button row and the full-width done button).
    *   It will not contain any business logic. The actions for each button will be provided via callbacks (`Runnable` or `Consumer`) in its constructor.
*   **Structure:**
    *   Extend `AbstractWidget`.
    *   **Fields:** References to the buttons it contains.
    *   **Constructor:** Will take its position/dimensions and a record/class containing all the necessary callbacks.
        ```java
        // Example callbacks structure
        public record ActionCallbacks(Runnable onImport, Runnable onCape, Runnable onDone, ...)
        
        public ActionButtonsPanel(..., ActionCallbacks callbacks) { ... }
        ```
    *   **`init()` or Constructor Logic:** Will create the buttons, assign the callbacks to their `onPress` actions, and perform all the layout math.

---

#### 5. `LinkButtonsPanel.java` (New Class)

A simple panel for the social/link buttons in the top-right.

*   **Responsibilities:**
    *   Contain the `LinkButton`s for Discord, CurseForge, Modrinth, and Settings.
    *   Handle their horizontal layout.
*   **Structure:**
    *   Extend `AbstractWidget`.
    *   **Constructor:** Will take its position/dimensions. It can create its children directly since their actions are self-contained (opening a URL) or simple (like the settings button).

---

### Refactoring Steps (High-Level)

1.  **Create New Files:** Create the empty class files for `SkinListPanel`, `PlayerPreviewPanel`, `ActionButtonsPanel`, and `LinkButtonsPanel` in the new `panel` package. Make them extend a suitable widget container class.
2.  **Migrate Player Preview:**
    *   Copy the creation and layout logic for `playerWidget`, model buttons, and `rotateButton` from `PlayerSkinMenuScreen.init()` into `PlayerPreviewPanel`.
    *   Move the `currentModelType` field and `setModelType`/`updateModelButtonStates` methods into `PlayerPreviewPanel`.
3.  **Migrate Action Buttons:**
    *   Copy the creation and layout logic for the bottom buttons (`import`, `cape`, `done`, etc.) into `ActionButtonsPanel`.
    *   Modify the button creation to use callbacks passed into the panel's constructor.
4.  **Migrate Other Panels:** Do the same for `LinkButtonsPanel` and `SkinListPanel`.
5.  **Refactor `PlayerSkinMenuScreen`:**
    *   Remove all the migrated code and fields.
    *   In `init()`, add the new code to instantiate and position the four new panels.
    *   Wire up the communication callbacks (e.g., `skinListPanel.setOnSkinSelected(...)`).
6.  **Test and Cleanup:** Ensure all functionality remains the same. Remove any unused imports or private methods from the main screen class.

### Benefits of this Refactor

*   **Readability:** `PlayerSkinMenuScreen` will become very easy to understand, showing the high-level structure of the screen at a glance.
*   **Maintainability:** If you need to change the layout of the bottom buttons, you only need to edit `ActionButtonsPanel.java`, without touching the other components.
*   **Reusability:** The `PlayerPreviewPanel` could potentially be reused in other screens (like a future cape selection screen) with minimal changes.
*   **Isolation:** Bugs in the layout of one panel are isolated to that panel's code, making debugging easier.
*   **Clearer Responsibilities:** Each class will have a clear and distinct purpose, following best practices for software design.