// Build with "go build launcher_windows.go" on the Windows packaging host.
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

func main() {
	launcher, err := os.Executable()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	root := filepath.Dir(launcher)
	// Keep argv as individual strings, including empty note values. The Java
	// launcher is part of our runtime and never comes from the caller's PATH.
	args := []string{"-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
		"-cp", filepath.Join(root, "app", "*"), "com.deadliner.cli.MainKt"}
	cmd := exec.Command(filepath.Join(root, "runtime", "bin", "java.exe"), append(args, os.Args[1:]...)...)
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		if exit, ok := err.(*exec.ExitError); ok {
			os.Exit(exit.ExitCode())
		}
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
