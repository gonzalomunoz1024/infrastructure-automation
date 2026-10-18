// Command ip-pool serves the example provider: run (the default) serves it until the process is
// stopped, publish [file] writes the provider file, submit sends it to the platform.
package main

import (
	"context"
	"fmt"
	"log"
	"os"

	"github.com/gonzalomunoz1024/infrastructure-automation/sdk/go/provider"
)

func main() {
	p := NewPools(DefaultPools()).Provider()
	cmd := "run"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	ctx := context.Background()
	switch cmd {
	case "run":
		if err := provider.Run(ctx, p, provider.OptionsFromEnv()); err != nil {
			log.Fatal(err)
		}
	case "publish":
		file := "providers/" + p.Owner() + "/" + p.Name() + ".yaml"
		if len(os.Args) > 2 {
			file = os.Args[2]
		}
		if err := provider.WriteFile(p, file); err != nil {
			log.Fatal(err)
		}
		fmt.Println("Wrote", file)
	case "submit":
		s, err := provider.Submit(ctx, p)
		if err != nil {
			log.Fatal(err)
		}
		fmt.Printf("Change request %s (%s) opened for %s %s\n", s.ChangeRequest, s.Status, p.Name(), p.Version())
	default:
		fmt.Fprintln(os.Stderr, "usage: ip-pool [run | publish [file] | submit]")
		os.Exit(2)
	}
}
