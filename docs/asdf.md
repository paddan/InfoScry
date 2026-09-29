# Java and Node.js with asdf

[Installation](installation.md) · [Development](development.md)

Use asdf to select Java and Node.js per checkout. Tesseract, OCR language data
and Calibre are still installed as described in
[local dependencies](installation.md#install-local-dependencies).

## Install asdf and configure zsh

These instructions use modern asdf (0.16 or newer). Install it and the plugin
dependencies with Homebrew:

```bash
brew install asdf bash coreutils git gpg gawk
asdf --version
```

The Java plugin needs Bash 5 or newer, curl and unzip; macOS supplies curl and
unzip, while Homebrew supplies the newer Bash. The Node.js plugin setup uses
gpg and gawk. See the [asdf setup guide](https://asdf-vm.com/guide/getting-started.html)
and [Java plugin requirements](https://github.com/halcyon/asdf-java).

Add the shims directory to `~/.zshrc`, after Homebrew's shell setup, and apply
the same setting in your current terminal:

```bash
export PATH="${ASDF_DATA_DIR:-$HOME/.asdf}/shims:$PATH"
```

Keep these shims ahead of other Java/Node directories in `PATH` so the selected
asdf versions are used. Open a new terminal after editing your shell config.

## Add the plugins

Check existing plugins first and add only those that are missing:

```bash
asdf plugin list
asdf plugin add java https://github.com/halcyon/asdf-java.git
asdf plugin add nodejs https://github.com/asdf-vm/asdf-nodejs.git
```

Plugin sources: [asdf-java](https://github.com/halcyon/asdf-java) and
[asdf-nodejs](https://github.com/asdf-vm/asdf-nodejs).

## Install and select project versions

Run these commands from the InfoScry repository root:

```bash
cat .tool-versions
asdf install
asdf install nodejs latest:24
asdf set nodejs latest:24
export JAVA_HOME="$(asdf where java)"
```

`asdf install` installs versions in the checkout's
[.tool-versions](../.tool-versions), which currently pins Java 25. Node is not
yet pinned there; the next two commands install a stable Node 24 release and
record its exact version in your checkout's `.tool-versions`.

`asdf set` changes that local project file. It does not change the defaults in
your home directory. Use `asdf set`, rather than the older `asdf local` syntax;
see [version selection](https://asdf-vm.com/manage/versions.html).

If maintaining a shared Node pin, commit the selected exact version through the
project's normal Git workflow. Once both runtimes are pinned, new checkouts
only need `asdf install` and the `JAVA_HOME` setup.

## Set JAVA_HOME and verify

Set `JAVA_HOME` from the project directory before running Gradle:

```bash
export JAVA_HOME="$(asdf where java)"
asdf current
command -v java
command -v node
java -version
node --version
npm --version
```

Check Java 25, Node 24 and that Java/Node resolve through asdf's shims. A tool
without a project pin may inherit its version from a parent `.tool-versions`,
including the one in your home directory; `asdf current` shows the source.

For automatic `JAVA_HOME` updates as you move between projects, the Java plugin
provides a zsh helper. After installing the plugin, add this to `~/.zshrc`:

```bash
source "${ASDF_DATA_DIR:-$HOME/.asdf}/plugins/java/set-java-home.zsh"
```

See the [Java plugin's JAVA_HOME instructions](https://github.com/halcyon/asdf-java#java_home).
Do not keep a competing fixed Homebrew `JAVA_HOME` export after this helper.

Continue with [building InfoScry](installation.md#build-from-source). Gradle
comes from `./gradlew`; an asdf Gradle plugin is unnecessary for this project.
