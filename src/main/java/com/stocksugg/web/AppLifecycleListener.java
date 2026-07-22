package com.stocksugg.web;

import com.stocksugg.stock.BatchScheduler;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/** Starts/stops the daily batch scheduler with the Tomcat webapp lifecycle. */
@WebListener
public final class AppLifecycleListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        BatchScheduler.start();
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        BatchScheduler.stop();
    }
}
