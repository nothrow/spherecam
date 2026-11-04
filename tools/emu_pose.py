"""Windows-side helper: sets the Android emulator's device rotation over its gRPC API.

Runs under a *Windows* Python with grpcio + grpcio-tools (the emulator's gRPC port and token are only
reachable from Windows). Called by emu_capture.py through WSL interop:
    emu_pose.py set <x> <y> <z>     # ROTATION physical model, degrees, R_ew_from_device = Rx(x) Ry(y) Rz(z)
    emu_pose.py get
The emulator world (ew) is Y-up; this was established by probing accelerometer/magnetometer readings.
"""
import glob
import os
import re
import subprocess
import sys

import grpc

ini = {}
for f in glob.glob(os.path.expandvars(r"%LOCALAPPDATA%\Temp\avd\running\pid_*.ini")):
    ini.update(l.strip().split("=", 1) for l in open(f) if "=" in l)

# Stubs are generated per machine from the proto shipped with the running emulator, so they always match it.
stubs = os.path.expandvars(r"%LOCALAPPDATA%\spherecam-emu")
if not os.path.exists(os.path.join(stubs, "emulator_controller_pb2_grpc.py")):
    exe = re.search(r'"([^"]*qemu-system[^"]*)"', ini["cmdline"]).group(1).replace("\\\\", "\\")
    lib = os.path.join(os.path.dirname(exe), "..", "..", "lib")
    os.makedirs(stubs, exist_ok=True)
    subprocess.check_call([sys.executable, "-m", "grpc_tools.protoc", "-I", lib,
                           f"--python_out={stubs}", f"--grpc_python_out={stubs}", "emulator_controller.proto"])
sys.path.insert(0, stubs)
import emulator_controller_pb2 as pb  # noqa: E402
import emulator_controller_pb2_grpc as rpc  # noqa: E402

stub = rpc.EmulatorControllerStub(grpc.insecure_channel("127.0.0.1:" + ini["grpc.port"]))
auth = [("authorization", "Bearer " + ini["grpc.token"])]
P = pb.PhysicalModelValue

if sys.argv[1] == "set":
    stub.setPhysicalModel(P(target=P.ROTATION, value=pb.ParameterValue(data=list(map(float, sys.argv[2:5])))), metadata=auth)
r = stub.getPhysicalModel(P(target=P.ROTATION), metadata=auth).value.data
print(" ".join(f"{v:.3f}" for v in r))
