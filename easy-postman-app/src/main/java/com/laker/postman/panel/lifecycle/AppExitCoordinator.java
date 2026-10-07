package com.laker.postman.panel.lifecycle;

import com.laker.postman.common.UiSingletonFactory;
import com.laker.postman.common.exception.CancelException;
import com.laker.postman.frame.MainFrame;
import com.laker.postman.ioc.Component;
import com.laker.postman.panel.collections.OpenedRequestTabSessionSaver;
import com.laker.postman.panel.functional.FunctionalPanel;
import com.laker.postman.panel.performance.PerformancePanel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class AppExitCoordinator {

    /**
     * 显示退出确认对话框，处理未保存内容。
     */
    public void exitApplication() {
        exitApplication(false);
    }

    /** Best-effort saves current UI state on the EDT and exits without a confirmation dialog. */
    public void exitForRestart() {
        exitApplication(true);
    }

    /** Saves before exit; replacement launches proceed even if their best-effort snapshot fails. */
    private void exitApplication(boolean restart) {

        // 保存所有打开的请求（包括未保存的和已保存的）
        try {
            if (restart) {
                OpenedRequestTabSessionSaver.saveOpenTabsForRestart();
            } else {
                OpenedRequestTabSessionSaver.saveOpenTabsOnExit();
            }
        } catch (CancelException e) {
            // 用户取消了保存操作，终止退出
            return;
        } catch (RuntimeException e) {
            if (!restart) {
                throw e;
            }
            log.warn("Failed to snapshot open tabs before GUI replacement", e);
        }

        // 保存功能测试配置
        try {
            UiSingletonFactory.getExistingInstance(FunctionalPanel.class)
                    .ifPresent(FunctionalPanel::save);
        } catch (Exception e) {
            log.error("Failed to save functional test config on exit", e);
        }

        // 保存性能测试配置
        try {
            UiSingletonFactory.getExistingInstance(PerformancePanel.class)
                    .ifPresent(PerformancePanel::save);
        } catch (Exception e) {
            log.error("Failed to save performance test config on exit", e);
        }

        // 没有未保存内容，或已处理完未保存内容，直接退出
        log.info("Exiting application: restart={}", restart);
        UiSingletonFactory.getInstance(MainFrame.class).dispose();
        System.exit(0);
    }
}
