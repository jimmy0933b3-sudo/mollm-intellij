package tw.com.fmt.mollm;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

final class PermissionDialog extends DialogWrapper {
    private final Path root;
    private final DirectoryPermissions permissions;
    private final JCheckBox files = new JCheckBox("此工作目錄下永遠允許檔案修改／新增");
    private final JCheckBox commands = new JCheckBox("此工作目錄下永遠允許執行指令");
    private final DefaultListModel<String> rows = new DefaultListModel<>();
    private final JList<String> list = new JList<>(rows);
    private final DefaultListModel<String> mcpRows = new DefaultListModel<>();
    private final JList<String> mcpList = new JList<>(mcpRows);
    private final JLabel status = new JLabel("未勾選的工具，每次詢問允許一次。永久授權只適用目前 Agent 工作目錄。");
    private java.util.List<MollmSettings.DirectoryGrant> grants;
    private java.util.List<MollmSettings.McpToolGrant> mcpGrants;

    PermissionDialog(Project project, Path root, DirectoryPermissions permissions) {
        super(project, false);
        this.root = root; this.permissions = permissions;
        files.setSelected(permissions.allows(root, root, DirectoryPermissions.Kind.FILES));
        commands.setSelected(permissions.allows(root, root, DirectoryPermissions.Kind.COMMANDS));
        setTitle("MoLLM Agent 權限管理"); setOKButtonText("儲存目前目錄權限"); setCancelButtonText("關閉");
        refresh(); init();
    }
    @Override protected JComponent createCenterPanel() {
        var panel = new JPanel(new BorderLayout(8, 8)); panel.setPreferredSize(new Dimension(750, 420));
        var options = new JPanel(new GridLayout(0, 1, 4, 4));
        var scope = new JTextArea("目前 Agent 工作目錄：" + root + "\n"
                + "讀取／搜尋本來就可執行；檔案修改與指令權限分開儲存。更換工作目錄需單獨授權。\n"
                + "指令的工作目錄不是作業系統沙箱，永久允許指令後仍可影響目錄外檔案或存取網路。");
        scope.setEditable(false); scope.setLineWrap(true); scope.setWrapStyleWord(true);
        options.add(scope); options.add(files); options.add(commands);
        panel.add(options, BorderLayout.NORTH);
        var tabs = new JTabbedPane();
        var directoryTab = new JPanel(new BorderLayout(4, 4));
        directoryTab.add(new JScrollPane(list), BorderLayout.CENTER);
        var revoke = new JButton("撤銷選取目錄的永久授權");
        revoke.addActionListener(event -> {
            int index = list.getSelectedIndex(); if (index < 0) return;
            var grant = grants.get(index); permissions.revoke(grant.directory);
            if (DirectoryPermissions.samePath(grant.directory, root)) {
                files.setSelected(permissions.allows(root, root, DirectoryPermissions.Kind.FILES));
                commands.setSelected(permissions.allows(root, root, DirectoryPermissions.Kind.COMMANDS));
            }
            refresh(); status.setText("已撤銷該目錄的永久授權；之後的修改與指令會重新詢問。");
        });
        directoryTab.add(revoke, BorderLayout.SOUTH);
        tabs.addTab("檔案與指令", directoryTab);

        var mcpTab = new JPanel(new BorderLayout(4, 4));
        var note = new JTextArea("每筆永久授權限定指定工作目錄、MCP 伺服器及工具。"
                + "同一工具即使使用不同參數也會直接執行；伺服器連線設定變更後會重新詢問。");
        note.setEditable(false); note.setLineWrap(true); note.setWrapStyleWord(true);
        mcpTab.add(note, BorderLayout.NORTH);
        mcpTab.add(new JScrollPane(mcpList), BorderLayout.CENTER);
        var revokeMcp = new JButton("撤銷選取 MCP 工具的永久授權");
        revokeMcp.addActionListener(event -> {
            int index = mcpList.getSelectedIndex(); if (index < 0) return;
            permissions.revokeMcp(mcpGrants.get(index));
            refresh(); status.setText("已撤銷該 MCP 工具的永久授權；之後會重新詢問。");
        });
        mcpTab.add(revokeMcp, BorderLayout.SOUTH);
        tabs.addTab("MCP 工具", mcpTab);
        panel.add(tabs, BorderLayout.CENTER);
        panel.add(status, BorderLayout.SOUTH);
        return panel;
    }
    private void refresh() {
        grants = permissions.grants(); rows.clear();
        for (var grant : grants) rows.addElement(grant.directory + "  ["
                + (grant.allowFiles ? "檔案修改 " : "") + (grant.allowCommands ? "指令執行" : "") + "]");
        mcpGrants = permissions.mcpGrants(); mcpRows.clear();
        for (var grant : mcpGrants) {
            var name = MollmSettings.getInstance().mcpServers().stream()
                    .filter(server -> server.id.equals(grant.serverId))
                    .map(server -> server.name).findFirst().orElse("已移除的伺服器");
            mcpRows.addElement(grant.directory + "  [" + name + " / " + grant.toolName + "]");
        }
    }
    @Override protected void doOKAction() {
        try { permissions.set(root, files.isSelected(), commands.isSelected()); super.doOKAction(); }
        catch (java.io.IOException e) { status.setText("無法儲存權限：" + e.getMessage()); }
    }
}
