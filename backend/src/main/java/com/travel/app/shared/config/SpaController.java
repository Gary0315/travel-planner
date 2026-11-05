package com.travel.app.shared.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 將所有非 API、非靜態資源的路由導向 React 的 index.html，
 * 讓 React Router 在客戶端接手處理（SPA fallback）。
 */
@Controller
public class SpaController {

    // 注意：React Router 每新增一個 top-level 路由（main.tsx 的 <Route path>），這裡就要同步加一條，
    // 否則 prod 直開該網址或 F5 會 404（dev 沒事，Vite 自帶 fallback，所以本機測不出來）。
    @RequestMapping(value = {"/view/**", "/trips", "/"})
    public String forward() {
        return "forward:/index.html";
    }
}
