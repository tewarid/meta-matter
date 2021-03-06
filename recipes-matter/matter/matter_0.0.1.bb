DESCRIPTION = "Matter is the foundation for connected things."
HOMEPAGE = "https://buildwithmatter.com"

SRC_URI = " \
    gitsm://github.com/tewarid/matter.git;protocol=https;branch=master \
    file://matter.service \
    file://0001-Do-not-hard-code-clang-target-for-arm.patch \
"
SRCREV = "${AUTOREV}"

S = "${WORKDIR}/git"

# Only clang is supported for now.
TOOLCHAIN = "clang"
TOOLCHAIN:class-native = "clang"

# This makes the target build use libc++ and compiler_rt instead of the GNU
# runtime. The native binaries compiled and run as part of the build still use
# libstdc++ and libgcc though (see https://github.com/kraj/meta-clang/issues/449).
RUNTIME = "llvm"

LICENSE = "Apache-2.0"
LIC_FILES_CHKSUM = "\
    file://${S}/LICENSE;md5=86d3f3a95c324c9479bd8986968f4327 \
    file://${S}/third_party/openthread/repo/LICENSE;md5=543b6fe90ec5901a683320a36390c65f \
    "

require gn-utils.inc

# Override in local.conf to determine what to build
MATTER_APP_DIR ?= "examples/all-clusters-app/linux"
MATTER_APP_BIN ?= "chip-all-clusters-app"

# The actual directory name in out/ is irrelevant for GN.
MATTER_OUT_DIR ?= "out/Release"

B = "${S}/${MATTER_APP_DIR}/${MATTER_OUT_DIR}"

# Append instead of assigning;
# adds packages to DEPENDS.
DEPENDS += " \
    python3-native \
    python3-pip-native \
    glib-2.0-native \
    gn-native \
    ninja-native \
    glib-2.0 \
    avahi \
    pkgconfig-native \
"

# Enable systemd service
inherit systemd
SYSTEMD_SERVICE:${PN} = "matter.service"
SYSTEMD_AUTO_ENABLE = "enable"

COMPATIBLE_MACHINE = "(-)"
COMPATIBLE_MACHINE:aarch64 = "(.*)"
COMPATIBLE_MACHINE:armv6 = "(.*)"
COMPATIBLE_MACHINE:armv7a = "(.*)"
COMPATIBLE_MACHINE:armv7ve = "(.*)"
COMPATIBLE_MACHINE:x86 = "(.*)"
COMPATIBLE_MACHINE:x86-64 = "(.*)"

# Also build the parts that are run on the host with clang.
BUILD_AR:toolchain-clang = "llvm-ar"
BUILD_CC:toolchain-clang = "clang"
BUILD_CXX:toolchain-clang = "clang++"
BUILD_LD:toolchain-clang = "clang"

# Make sure pkg-config, when used with the host's toolchain to build the
# binaries we need to run on the host, uses the right pkg-config to avoid
# passing include directories belonging to the target.
GN_ARGS += 'host_pkg_config="pkg-config-native"'

# Tell GN whether we want a debug build or not
GN_ARGS += '${@oe.utils.conditional('DEBUG_BUILD','1','is_debug=true','is_debug=false',d)}'

# Use lld linker, it's quicker, see https://lld.llvm.org/#performance
GN_ARGS += "use_lld=true"

# Don't treat compiler warning as errors, caused by clang version mismatch
GN_ARGS += "treat_warnings_as_errors=false"

# Toolchains we will use for the build. We need to point to the toolchain file
# we've created, set the right target architecture etc.
GN_ARGS += ' \
    custom_toolchain="//build/toolchain/yocto:yocto_target" \
    host_toolchain="//build/toolchain/yocto:yocto_native" \
    is_clang=true \
    target_cpu="${@gn_target_arch_name(d)}" \
'

# ARM builds need special additional flags (see ${S}/build/config/arm.gni).
# If we do not pass |arm_arch| and friends to GN, it will deduce a value that
# will then conflict with TUNE_CCARGS and CC.
def get_compiler_flag(params, param_name, d):
    """Given a sequence of compiler arguments in |params|, returns the value of
    an option |param_name| or an empty string if the option is not present."""
    for param in params:
      if param.startswith(param_name):
        return param.split('=')[1]
    return ''

ARM_FLOAT_ABI = "${@bb.utils.contains('TUNE_FEATURES', 'callconvention-hard', 'hard', 'softfp', d)}"
ARM_FPU = "${@get_compiler_flag(d.getVar('TUNE_CCARGS').split(), '-mfpu', d)}"
ARM_TUNE = "${@get_compiler_flag(d.getVar('TUNE_CCARGS').split(), '-mcpu', d)}"
ARM_VERSION:aarch64 = "8"
ARM_VERSION:armv7a = "7"
ARM_VERSION:armv7ve = "7"
ARM_VERSION:armv6 = "6"

# GN computes and defaults to it automatically where needed
# forcing it from cmdline breaks build on places where it ends up
# overriding what GN wants
TUNE_CCARGS:remove = "-mthumb"

GN_ARGS:append:arm = ' \
    arm_float_abi="${ARM_FLOAT_ABI}" \
    arm_fpu="${ARM_FPU}" \
    arm_tune="${ARM_TUNE}" \
'

python do_write_toolchain_file () {
    """Writes a BUILD.gn file for Yocto detailing its toolchains."""
    write_toolchain_file(d, d.expand("${MATTER_APP_DIR}"))
}
addtask write_toolchain_file after do_patch before do_configure

do_configure() {
    ln -f -s ${TMPDIR}/hosttools/python3 ${TMPDIR}/hosttools/python
    cd ${S}/${MATTER_APP_DIR}
    # Workaround for https://github.com/project-chip/connectedhomeip/issues/16844
    touch build_overrides/pigweed_environment.gni
    gn gen --args='${GN_ARGS}' "${MATTER_OUT_DIR}"
}

do_compile() {
    cd ${S}/${MATTER_APP_DIR}
    ninja -C "${MATTER_OUT_DIR}"
}
do_compile[progress] = "outof:^\[(\d+)/(\d+)\]\s+"

do_install() {
    install -d ${D}${bindir}
    install -Dm 0755 ${S}/${MATTER_APP_DIR}/${MATTER_OUT_DIR}/${MATTER_APP_BIN} ${D}${bindir}/matterd
    # install systemd unit files
    install -d ${D}${systemd_unitdir}/system
    install -m 0644 ${WORKDIR}/matter.service ${D}${systemd_unitdir}/system
}

FILES:${PN} = " \
    ${bindir}/* \
    ${systemd_unitdir}/* \
"

PACKAGE_DEBUG_SPLIT_STYLE = "debug-without-src"

# There is no need to ship empty -dev packages.
ALLOW_EMPTY:${PN}-dev = "0"
