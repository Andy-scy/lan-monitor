package main

// util.go — 基础设施：滚动日志、调试输出、杂项。

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

var (
	logMu       sync.Mutex
	logFile     *os.File
	logSize     int64
	logDirPath string
)

const (
	logMaxSize = 512 << 10 // 512KB
	logKeep    = 3
)

func initLog(dir string, console bool) {
	_ = os.MkdirAll(dir, 0o755)
	logDirPath = dir
	path := filepath.Join(dir, "monitor.log")
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return
	}
	if st, err := f.Stat(); err == nil {
		logSize = st.Size()
	}
	logFile = f
}

func logf(format string, args ...any) {
	line := time.Now().Format("2006-01-02 15:04:05 ") + fmt.Sprintf(format, args...) + "\n"
	logMu.Lock()
	defer logMu.Unlock()
	if logFile != nil {
		n, _ := logFile.WriteString(line)
		logSize += int64(n)
		if logSize > logMaxSize {
			logFile.Close()
			rotateLogs()
			f, err := os.OpenFile(logFilePath(), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
			if err == nil {
				logFile, logSize = f, 0
			} else {
				logFile = nil
			}
		}
	}
	fmt.Print(line) // windowsgui 子系统下无副作用；-console 模式可见
}

func logFilePath() string { return filepath.Join(logDirPath, "monitor.log") }

func rotateLogs() {
	for i := logKeep - 1; i >= 1; i-- {
		os.Rename(fmt.Sprintf("%s.%d", logFilePath(), i), fmt.Sprintf("%s.%d", logFilePath(), i+1))
	}
	os.Rename(logFilePath(), logFilePath()+".1")
}

var debugMode = false

func dbg(format string, args ...any) {
	if debugMode {
		logf("[dbg] "+format, args...)
	}
}

func fileExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && !st.IsDir()
}

func exeDir() string {
	exe, err := os.Executable()
	if err != nil {
		wd, _ := os.Getwd()
		return wd
	}
	return filepath.Dir(exe)
}
