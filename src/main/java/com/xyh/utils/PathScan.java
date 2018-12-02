package com.xyh.utils;

import com.xyh.utils.Common.OkHttpEnums;
import com.xyh.utils.core.OkhttpConnect;
import com.xyh.utils.model.OkHttpRequest;
import com.xyh.utils.model.OkHttpResponse;
import lombok.Getter;
import lombok.Setter;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author xuanyh
 * @create 2018-12-02 20:32
 **/
@Setter
@Getter
public class PathScan {
    private static Logger log = Logger.getLogger(PathScan.class.toString());

    static {
        log.setLevel(Level.INFO);
    }

    private LoadConfig loadConfig;

    private ExecutorService executorService;
    private static final int DEFAULT_THREADS = 32;
    private static final String THREADS_CONFIG_KEY = "threads";

    private static final int DEFAULT_CONNECT_TIMEOUT = 500;
    private static final String CONNECT_TIMEOUT_CONFIG_KEY = "connect-timeout";
    private static final int DEFAULT_READ_TIMEOUT = 500;
    private static final String READ_TIMEOUT_CONFIG_KEY = "read-timeout";

    private int bucketSize;
    private int threads;
    private int connectTimeout;
    private int readTimeout;

    private List<ScanResult> scanResults = new ArrayList<>();

    public void init() {
        try {
            loadConfig = LoadConfig.build();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        Objects.requireNonNull(loadConfig);
        String threadsStr = loadConfig.getConfig().get(THREADS_CONFIG_KEY);
        if (threadsStr != null) {
            threads = Integer.parseInt(threadsStr);
            if (threads <= 0 || threads >= 5000)
                log.severe("线程数配置出错！");
            executorService = Executors.newFixedThreadPool(threads);
        }
        String connectTimeoutStr = loadConfig.getConfig().get(DEFAULT_CONNECT_TIMEOUT);
        if (connectTimeoutStr != null) {
            connectTimeout = Integer.parseInt(connectTimeoutStr);
        }
        String readTimeoutStr = loadConfig.getConfig().get(DEFAULT_READ_TIMEOUT);
        if (readTimeoutStr != null) {
            readTimeout = Integer.parseInt(readTimeoutStr);
        }
    }

    public void scan(String host) {
        if (host.charAt(host.length() - 1) != '/')
            host += '/';
        List<String> paths = loadConfig.getPaths();
        List<ScanResult> scanResults = new ArrayList<>();
        bucketSize = loadConfig.getPaths().size() / threads;
        CountDownLatch countDownLatch = new CountDownLatch(threads);
        AtomicLong progess = new AtomicLong(0);
        for (int i = 0; i < threads; i++) {
            List<String> mPaths;
            if (i == threads - 1) {
                mPaths = paths.subList(i * bucketSize, paths.size());
            } else {
                mPaths = paths.subList(i * bucketSize, (i + 1) * bucketSize);
            }
            String finalHost = host;
            executorService.execute(() -> {
                for (String path : mPaths) {
                    String url = finalHost + path;
                    int code = httpRequest(url);
                    if (code != 404) {
                        log.info("[ " + code + "] " + url);
                        ScanResult scanResult = new ScanResult();
                        scanResult.setUrl(url);
                        scanResult.setResponseCode(code);
                        scanResults.add(scanResult);
                    }
                    if (progess.incrementAndGet() % 100 == 0 || progess.get() == paths.size())
                        log.info("progess -> " + progess.get() + "/" + paths.size());

                }
                countDownLatch.countDown();
            });
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        ExportFile.exportFile(host, scanResults);
        executorService.shutdown();
    }

    private int httpRequest(String url) {
        OkHttpRequest okHttpRequest = new OkHttpRequest.Builder()
                .url(url)
                .timeout(connectTimeout, readTimeout, TimeUnit.MILLISECONDS)
                .method(OkHttpEnums.Method.GET)
                .build();
        try {
            OkHttpResponse okHttpResponse = OkhttpConnect.send(okHttpRequest);
            int code = okHttpResponse.getCode();
            okHttpResponse.getResponse().close();
            return code;
        } catch (SocketTimeoutException e) {
            return 404;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 404;
    }
}