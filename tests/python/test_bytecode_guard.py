import sys


def test_bytecode_writing_is_disabled():
    # conftest.py sets this so a bare pytest run leaves no __pycache__ in the Chaquopy source dir.
    assert sys.dont_write_bytecode is True
