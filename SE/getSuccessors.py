"""
Author: Thomas Peterson
Year: 2019
"""
#Built in modules
import sys, logging

#Custom modules
import symbolicExecutor

logger = logging.getLogger(__name__)

def main():
    if (len(sys.argv) < 3):
        print("Usage: Python getSuccessors.py [path to binary] [address of instruction]")
        sys.exit(0)
    program = sys.argv[1]
    address = int(sys.argv[2],16)
    print("Running with program="+program+" address="+hex(address))
    #paths = [[0x08048080,0x08048085,0x0804808a,0x0804808f,0x08048094,0x08048096,0x0804809b],
    #         [0x08048080,0x08048085,0x0804808a,0x0804808f,0x08048094]]
    #pathslen = len(paths)
    symbolicExecutor.execute(program,address)

if __name__ == "__main__":
    main()
