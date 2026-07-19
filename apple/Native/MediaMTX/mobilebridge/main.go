package main

/*
#include <stdint.h>
#include <stdlib.h>

typedef void (*R2CMediaMTXLogCallback)(uintptr_t context, const char *line);

static inline void R2CMediaMTXInvokeLogCallback(
    R2CMediaMTXLogCallback callback,
    uintptr_t context,
    const char *line
) {
    if (callback != NULL) {
        callback(context, line);
    }
}
*/
import "C"

import (
	"bufio"
	"os"
	"sync"
	"unsafe"

	"github.com/bluenviron/mediamtx/internal/core"
)

var instance struct {
	sync.Mutex
	server      *core.Core
	logWriter   *os.File
	logCallback C.R2CMediaMTXLogCallback
	logContext  C.uintptr_t
}

//export R2CMediaMTXSetLogCallback
func R2CMediaMTXSetLogCallback(callback C.R2CMediaMTXLogCallback, context C.uintptr_t) {
	instance.Lock()
	instance.logCallback = callback
	instance.logContext = context
	instance.Unlock()
}

//export R2CMediaMTXStart
func R2CMediaMTXStart(configPath *C.char) C.int {
	instance.Lock()
	defer instance.Unlock()

	if instance.server != nil {
		return 2
	}

	logReader, logWriter, err := os.Pipe()
	if err != nil {
		return 3
	}

	originalStdout := os.Stdout
	os.Stdout = logWriter
	server, ok := core.New([]string{C.GoString(configPath)})
	os.Stdout = originalStdout
	if !ok {
		logWriter.Close()
		logReader.Close()
		return 1
	}

	instance.server = server
	instance.logWriter = logWriter
	go forwardLogLines(logReader)
	return 0
}

//export R2CMediaMTXStop
func R2CMediaMTXStop() {
	instance.Lock()
	server := instance.server
	logWriter := instance.logWriter
	instance.server = nil
	instance.logWriter = nil
	instance.Unlock()

	if server != nil {
		server.Close()
	}
	if logWriter != nil {
		logWriter.Close()
	}
}

func forwardLogLines(reader *os.File) {
	defer reader.Close()

	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		instance.Lock()
		callback := instance.logCallback
		context := instance.logContext
		instance.Unlock()
		if callback == nil {
			continue
		}

		line := C.CString(scanner.Text())
		C.R2CMediaMTXInvokeLogCallback(callback, context, line)
		C.free(unsafe.Pointer(line))
	}
}

func main() {}
