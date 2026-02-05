import os
from pathlib import Path
from dulwich.server import FileSystemBackend
from dulwich.errors import NotGitRepository


def test_dulwich_path_logic():
    # Setup a dummy root
    root = "E:\\kryptonit\\onyx"
    # Ensure it ends with separator as Dulwich does internally
    root_dir = (os.path.abspath(root) + os.sep).replace(os.sep * 2, os.sep)
    print(f"Internal Root: {root_dir}")

    paths_to_test = ["", "/", "/onyx", "onyx"]

    for p in paths_to_test:
        clean_p = p.lstrip("/")
        # This mimics Dulwich internal open_repository logic
        abs_path = os.path.abspath(os.path.join(root_dir, clean_p))
        if not abs_path.endswith(os.sep):
            abs_path += os.sep

        print(f"Testing path: '{p}' -> Result: '{abs_path}'")
        if not abs_path.startswith(root_dir):
            print(f"  FAILED: '{abs_path}' does not start with '{root_dir}'")
        else:
            print(f"  SUCCESS")


if __name__ == "__main__":
    test_dulwich_path_logic()
