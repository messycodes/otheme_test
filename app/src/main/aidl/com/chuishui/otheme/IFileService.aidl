package com.chuishui.otheme;

interface IFileService {
    /**
     * 备份主题到指定路径
     * @param backupPath 备份文件保存路径（包含文件名）
     * @return 错误信息，成功则返回 null
     */
    String backupTheme(String backupPath) = 1;
    
    /**
     * 安装主题从指定路径（多主题模式，自动编号）
     * @param themePath 主题文件路径
     * @return 错误信息，成功则返回 null
     */
    String installTheme(String themePath) = 2;
    
    /**
     * 安装主题从文件描述符（多主题模式，自动编号）
     * @param pfd 主题文件的 ParcelFileDescriptor
     * @return 错误信息，成功则返回 null
     */
    String installThemeFromFd(in ParcelFileDescriptor pfd) = 7;
    
    /**
     * 获取主题目录的文件列表
     * @return 文件列表
     */
    List<String> getThemeInfo() = 3;
    
    /**
     * 获取所有主题及其信息（JSON 格式）
     * 格式: [{"name":"theme_1.theme","info":"<Author>...</Author>..."}, ...]
     * @return JSON 数组字符串，失败返回 null
     */
    String getThemeListWithInfo() = 10;
    
    /**
     * 删除指定主题文件
     * @param themeFileName 主题文件名（如 theme_1.theme）
     * @return 错误信息，成功则返回 null
     */
    String deleteTheme(String themeFileName) = 11;
    
    /**
     * 获取已安装主题的 themeInfo.xml 内容
     * @return XML 内容字符串，失败返回 null
     */
    String getInstalledThemeInfo() = 5;
    
    /**
     * 重启指定进程以应用主题
     * @param packages 需要重启的包名列表
     * @return 错误信息，成功则返回 null
     */
    String restartProcesses(in List<String> packages) = 6;
    
    /**
     * 检查指定应用是否已安装
     * @param packageName 包名
     * @return true 已安装，false 未安装
     */
    boolean isPackageInstalled(String packageName) = 8;
    
    /**
     * 卸载所有主题模块（恢复默认）
     * @return 错误信息，成功则返回 null
     */
    String uninstallTheme() = 9;
    
    void destroy() = 16777114; // Transaction code for destroy
}
