import os
from collections import Counter
import re
import matplotlib.pyplot as plt
from ISA import *
import random

def valid_format(s):
    pattern = r'^0x[0-9A-Fa-f]+/\d+-\d+/\d+-\d+-\d+$'
    
    return bool(re.match(pattern, s))

def generate_random_color():
    # Generate a random color in hex format
    random_color = "#{:06x}".format(random.randint(0, 0xFFFFFF))
    return random_color

class sigID:
    
    def __init__(self, device):
        self.device = device
        self.matchTable = [
                                'DISABLE',
                                
                                'ROB_ALLOC',
                                'ROB_ISSUE_LD',
                                'ROB_ISSUE_EX',
                                'ROB_ISSUE_ST',
                                'ROB_COMPLETE',
                                
                                'ENTER_LD_CTRL',
                                'ENTER_EX_CTRL',
                                'ENTER_ST_CTRL',
                                'LEAVE_LD_CTRL',   
                                'LEAVE_EX_CTRL',    
                                'LEAVE_ST_CTRL ',
                                
                                'ENTER_DMA_READ',
                                'ENTER_DMA_WRITE',
                                'LEAVE_DMA_READ',
                                'LEAVE_DMA_WRITE',

                                'ENTER_SPAD_READ',
                                'ENTER_SPAD_WRITE',
                                'LEAVE_SPAD_READ',
                                'LEAVE_SPAD_WRITE',

                                'ENTER_MESH_CTRL',
                                'LEAVE_MESH_CTRL',

                                'ENTER_DEL_MESH',
                                'LEAVE_DEL_MESH',
                                
                                'LD_CTRL_EXECUTE',
                                'EX_CTRL_EXECUTE',
                                'ST_CTRL_EXECUTE',
                                
                            ]
        
    def encode (self, signal):
        return self.matchTable[int(signal, 10)]    
        
class driver:
    
    def __init__ (self):
        self.sigID = sigID('gemmini')
        
    
    def get_stage_info(self, stage):
        stage_info = {}
        stage_name = sigID("gemmini").encode(stage[0])
        stage_info['stage'] = stage_name
        stage_info['cycle'] = int(stage[1], 10)
        return stage_info
        
    def get_cmd_info(self, cmd):
        
        gemmini_cmd = GemminiISA(cmd[0], cmd[1], cmd[2])
        cmd_info = gemmini_cmd.decode()
        return cmd_info
        
    def parse(self, line):
        
        line = line.replace(" ", "")
        line = line.replace("\n", "")
        if(not valid_format(line)):
            return None
        
        tokens = line.split("/")
        tokens[1] = tokens[1].split("-")
        tokens[2] = tokens[2].split("-")
        return tokens
        
    
    def translate(self, dst, src):
        datas = {}
        
        fsrc = open(src, 'r')
        
        while(True):
            
            line = fsrc.readline()
            if(not line):
                break
            
            tokens = self.parse(line)
            if(tokens):
                tag = tokens[0]
                stage_info = self.get_stage_info(tokens[1])
                cmd_info = self.get_cmd_info(tokens[2])
                
                if(tag in datas):
                    datas[tag]['stage_info'][stage_info['stage']] = stage_info['cycle']
                else:
                    datas[tag] = {'stage_info': {stage_info['stage'] : stage_info['cycle']}, 'cmd_info': {'raw_cmd': tokens[2], 'asm': cmd_info}}
        
        fsrc.close()
        
        return datas
    
    def write(self, dst, datas):
        
        fdst = open(dst, 'w')
        
        for tag in datas:
            print(tag)
            fdst.write("[Tag]={0}\n".format(tag))
            fdst.write("[Cmd Info]=\n")
            
            for info in datas[tag]['cmd_info']['asm']:
                fdst.write("\t\t[{0}]={1}\n".format(info, datas[tag]['cmd_info']['asm'][info]))
            
            fdst.write("\n[Stage Info]=\n")
            
            for stage in datas[tag]['stage_info']:
                fdst.write("\t\t[{0}]  {1}\n".format(stage, datas[tag]['stage_info'][stage]))
            fdst.write("\n\n")
    
    def visualize(self, datas):
        
        time_ranges = {'memory': [], 'compute': []}
        tags = {'memory': [], 'compute': []}
        labels = {'memory': [], 'compute': []}
        colors = {'memory': [], 'compute': []}
        visual_data = {}
        
        for tag in datas:
            
            cmd_info = datas[tag]['cmd_info']
            stage_info = datas[tag]['stage_info']
            
            instruction = GemminiISA(cmd_info['raw_cmd'][0], cmd_info['raw_cmd'][1], cmd_info['raw_cmd'][2])
            latency_info = instruction.get_latency(stage_info)
            
            if(latency_info):
                visual_data[tag] = {'Type': latency_info['Type'], 
                                    'Latency': latency_info['latency'], 
                                    'Label': cmd_info['asm']['inst'],
                                    'Color': generate_random_color()
                                    }
            
        fig, ax = plt.subplots(figsize=(12, 4))
        
        starts = [visual_data[tag]['Latency'][0] for tag in visual_data]
        durations = [visual_data[tag]['Latency'][1] for tag in visual_data]
        ends = [visual_data[tag]['Latency'][0] + visual_data[tag]['Latency'][1] for tag in visual_data]
        
        ax.axvspan(min(starts), max(ends), 60, facecolor='lightgrey', alpha=0.3)
        mem_ranges = []
        compute_ranges = []
        
        for tag in visual_data:
            if(visual_data[tag]['Type']=='memory'):
                ax.broken_barh([visual_data[tag]['Latency']], (10, 8), facecolors=visual_data[tag]['Color'], edgecolor='black')
                #ax.text(visual_data[tag]['Latency'][0]+visual_data[tag]['Latency'][1] / 2, 14, tag, ha='center', va='center', color='black', fontsize=10)
            if(visual_data[tag]['Type']=='compute'):
                ax.broken_barh([visual_data[tag]['Latency']], (0, 8), facecolors=visual_data[tag]['Color'], edgecolor='black')            
                #ax.text(visual_data[tag]['Latency'][0]+visual_data[tag]['Latency'][1] / 2, 4, tag, ha='center', va='center', color='black', fontsize=10)

        ax.set_xlim(min(starts), max(starts)+4000)        
        ax.get_yaxis().set_visible(True)
        ax.set_xlabel("Time")
        
    