// Copyright (c) 2014-2016 K Team. All Rights Reserved.
module AVERAGE-SPEC
  imports KERNELC

rule
  <fun>... FUN:Map ...</fun>
  <k>
    average(tv(int, X:Int), tv(int, Y:Int), tv(int, Z:Int))
  =>
    tv(int, (X +Int Y +Int Z) /Int 3)
  ...</k>
  <in> IN:List </in>
  <out> OUT:List </out>

endmodule
