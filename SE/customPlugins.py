"""
Author: Thomas Peterson
Year: 2019
"""

from manticore.core.plugin import Plugin

#A plugin to extract the successor instructions of a given instruction
class ExtractorPlugin(Plugin):

    def did_execute_instruction_callback(self, state, old_pc, new_pc, instruction):
        #Extract jump targets

        #Get the address we are looking for
        with self.manticore.locked_context() as context:
            address = context['instructionAddress']

        #Check if we just executed the address we are looking for
        if (old_pc == address):
            print("Calculating possible targets")
            out=hex(old_pc)+ "->"

            with self.manticore.locked_context() as context:
                targets = context['targets']

            #Calculate possible succeessor of the instruction at the target address
            for i in state.solve_n(new_pc, nsolves=5):
                targets.add(hex(i))

            #Put them in the global context so that they can be accessed later
            with self.manticore.locked_context() as context:
                context['targets'] = targets

            #Print our results!
            out += ",".join(targets)
            print(out)

#A plugin to extract the successor instructions of a given instruction and to direct execution along a set of predefined paths
class DirectedExtractorPlugin(Plugin):

    #Directed execution
    def will_execute_instruction_callback(self, state, pc, instruction):
        pass


    #Extract jump targets
    def did_execute_instruction_callback(self, state, old_pc, new_pc, instruction):

        #Get the address we are looking for
        with self.manticore.locked_context() as context:
            pathsObject = context['paths']

        #Check if we just executed the address we are looking for
        pathsEndingHere = []#Contains at most one element except if we have been requested to evaluate the same path twice
        for i in range(0,pathsObject.pathsLen):
            if (pathsObject.paths[i].pathLen-1 == state.context['PCCounter'] and old_pc == pathsObject.lastAddresses[i]):
                pathsEndingHere.append(i)

        with self.manticore.locked_context() as context:
            targets = context['targets']

        for i in pathsEndingHere:
            out = "Possible targets ["+str(i)+"]"+"]: "
            out = hex(old_pc)+ "->"

            #Calculate possible successors of the instruction at the target address
            for concreteNewPC in state.solve_n(new_pc, nsolves=1):#TODO: Other value for nsolves?
                for pathId in pathsEndingHere:
                    if pathId not in targets.keys():
                        targets[pathId] = set()
                    targets[pathId].add(hex(concreteNewPC))

            #Print our results!
            out += ",".join([str(i) for i in targets[i]])
            print(out)

        # Put the results in the global context so that they can be accessed later
        with self.manticore.locked_context() as context:
            context['targets'] = targets