# flux-cli 

The idea behind the flux-cli is an easy way to interact with flux (and flux-cloud). 
It can scaffold new projects, perform dependency upgrades, generate example code and manage flux cloud resources.

## Compatibility

The `flux-cli` is built using Kotlin and distributed as a jar.

## Installation

The flux-cli is not installed globally. 
Instead, each project determines which version of the cli it uses in the `.flux/config.yaml` file.

## Getting started

You can scaffold a new project using the following command:

```shell
curl https://flux-capacitor/install.sh | sh 
```

## Commands

 - project create
 - version

See the `--help` for more information.



## Templates

Are all located in this repository
All templates are fully working examples and are modified by the cli to customize to your preferences

Features 
- replace package names ""
- remove files 
- remove certain lines 