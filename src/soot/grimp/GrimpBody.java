/* Soot - a J*va Optimization Framework
 * Copyright (C) 1999 Patrick Lam
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 */

/*
 * Modified by the Sable Research Group and others 1997-1999.  
 * See the 'credits' file distributed with Soot for the complete list of
 * contributors.  (Soot is distributed at http://www.sable.mcgill.ca/soot)
 */






package soot.grimp;

import soot.*;
import soot.jimple.*;
import soot.jimple.internal.*;
import soot.jimple.toolkits.base.*;
import soot.grimp.toolkits.base.*;
import soot.toolkits.scalar.*;
import soot.util.*;
import java.util.*;
import soot.baf.*;
import java.io.*;

public class GrimpBody extends StmtBody
{
    /**
        Construct an empty GrimpBody 
     **/
     
    GrimpBody(SootMethod m)
    {
        super(m);
    }

    public Object clone()
    {
        Body b = Grimp.v().newBody(getMethod());
        b.importBodyContentsFrom(this);
        return b;
    }

    /**
        Constructs a GrimpBody from the given Body.
     */

    GrimpBody(Body body, Map options)
    {
        super(body.getMethod());

        boolean aggregateAllLocals = Options.getBoolean(options, "aggregate-all-locals");
        boolean noAggregating = Options.getBoolean(options, "no-aggregating");

        if(soot.Main.isVerbose)
            System.out.println("[" + getMethod().getName() + "] Constructing GrimpBody...");
        
        JimpleBody jBody = null;

        if(body instanceof ClassFileBody)
            jBody = Jimple.v().newBody(body, "gb.jb");
        else if (body instanceof JimpleBody)
            jBody = (JimpleBody)body;
        else
            throw new RuntimeException("Can only construct GrimpBody's from ClassFileBody's or JimpleBody's (for now)");

        Iterator it = jBody.getLocals().iterator();
        while (it.hasNext())
            getLocals().add(((Local)(it.next())));
            //            getLocals().add(((Local)(it.next())).clone());

        it = jBody.getUnits().iterator();

        final HashMap oldToNew = new HashMap(getUnits().size() * 2 + 1, 0.7f);
        LinkedList updates = new LinkedList();

        /* we should Grimpify the Stmt's here... */
        while (it.hasNext())
        {
            Stmt oldStmt = (Stmt)(it.next());
            final StmtBox newStmtBox = (StmtBox) Grimp.v().newStmtBox(null);
            final StmtBox updateStmtBox = (StmtBox) Grimp.v().newStmtBox(null);

            /* we can't have a general StmtSwapper on Grimp.v() */
            /* because we need to collect a list of updates */
            oldStmt.apply(new AbstractStmtSwitch()
            {
                public void caseAssignStmt(AssignStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newAssignStmt(s));
                }
                public void caseIdentityStmt(IdentityStmt s)
                  {
                    newStmtBox.setUnit(Grimp.v().newIdentityStmt(s));
                }
                public void caseBreakpointStmt(BreakpointStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newBreakpointStmt(s));
                }
                public void caseInvokeStmt(InvokeStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newInvokeStmt(s));
                }
                public void defaultCase(Stmt s)
                  {
                    throw new RuntimeException("invalid jimple stmt: "+s);
                }
                public void caseEnterMonitorStmt(EnterMonitorStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newEnterMonitorStmt(s));
                }
                public void caseExitMonitorStmt(ExitMonitorStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newExitMonitorStmt(s));
                }
                public void caseGotoStmt(GotoStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newGotoStmt(s));
                    updateStmtBox.setUnit(s);
                }
                public void caseIfStmt(IfStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newIfStmt(s));
                    updateStmtBox.setUnit(s);
                }
                public void caseLookupSwitchStmt(LookupSwitchStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newLookupSwitchStmt(s));
                    updateStmtBox.setUnit(s);
                }
                public void caseNopStmt(NopStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newNopStmt(s));
                }

                public void caseReturnStmt(ReturnStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newReturnStmt(s));
                }
                public void caseReturnVoidStmt(ReturnVoidStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newReturnVoidStmt(s));
                }
                public void caseTableSwitchStmt(TableSwitchStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newTableSwitchStmt(s));
                    updateStmtBox.setUnit(s);
                }
                public void caseThrowStmt(ThrowStmt s)
                {
                    newStmtBox.setUnit(Grimp.v().newThrowStmt(s));
                }
            });

            /* map old Expr's to new Expr's. */
            Stmt newStmt = (Stmt)(newStmtBox.getUnit());
            Iterator useBoxesIt = (Iterator)
                newStmt.getUseAndDefBoxes().iterator();
            while(useBoxesIt.hasNext())
                {
                    ValueBox b = (ValueBox) (useBoxesIt.next());
                    b.setValue(Grimp.v().newExpr(b.getValue()));
                }

            getUnits().add(newStmt);
            oldToNew.put(oldStmt, newStmt);
            if (updateStmtBox.getUnit() != null)
                updates.add(updateStmtBox.getUnit());
        }

        /* fixup stmt's which have had moved targets */
        it = updates.iterator();
        while (it.hasNext())
        {
            Stmt stmt = (Stmt)(it.next());

            stmt.apply(new AbstractStmtSwitch()
            {
                public void defaultCase(Stmt s)
                  {
                    throw new RuntimeException("Internal error: "+s);
                }
                public void caseGotoStmt(GotoStmt s)
                {
                    GotoStmt newStmt = (GotoStmt)(oldToNew.get(s));
                    newStmt.setTarget((Stmt)oldToNew.get(newStmt.getTarget()));
                }
                public void caseIfStmt(IfStmt s)
                {
                    IfStmt newStmt = (IfStmt)(oldToNew.get(s));
                    newStmt.setTarget((Stmt)oldToNew.get(newStmt.getTarget()));
                }
                public void caseLookupSwitchStmt(LookupSwitchStmt s)
                {
                    LookupSwitchStmt newStmt = 
                        (LookupSwitchStmt)(oldToNew.get(s));
                    newStmt.setDefaultTarget
                        ((Unit)(oldToNew.get(newStmt.getDefaultTarget())));
                    Unit[] newTargList = new Unit[newStmt.getTargetCount()];
                    for (int i = 0; i < newStmt.getTargetCount(); i++)
                        newTargList[i] = (Unit)(oldToNew.get
                                                (newStmt.getTarget(i)));
                    newStmt.setTargets(newTargList);
                }
                public void caseTableSwitchStmt(TableSwitchStmt s)
                {
                    TableSwitchStmt newStmt = 
                        (TableSwitchStmt)(oldToNew.get(s));
                    newStmt.setDefaultTarget
                        ((Unit)(oldToNew.get(newStmt.getDefaultTarget())));
                    int tc = newStmt.getHighIndex() - newStmt.getLowIndex()+1;
                    LinkedList newTargList = new LinkedList();
                    for (int i = 0; i < tc; i++)
                        newTargList.add(oldToNew.get
                                        (newStmt.getTarget(i)));
                    newStmt.setTargets(newTargList);
                }
            });
        }

        it = jBody.getTraps().iterator();
        while (it.hasNext())
        {
            Trap oldTrap = (Trap)(it.next());
            getTraps().add(Grimp.v().newTrap
                           (oldTrap.getException(),
                            (Unit)(oldToNew.get(oldTrap.getBeginUnit())),
                            (Unit)(oldToNew.get(oldTrap.getEndUnit())),
                            (Unit)(oldToNew.get(oldTrap.getHandlerUnit()))));
        }
        
        if(aggregateAllLocals)
        {
            Aggregator.v().transform(this, "gb.a");
            ConstructorFolder.v().transform(this, "gb.cf");
            Aggregator.v().transform(this, "gb.a");
            UnusedLocalEliminator.v().transform(this, "gb.ule");
        }
        else if (!noAggregating)
        {
            Aggregator.v().transform(this, "gb.asv1", "only-stack-locals");
            ConstructorFolder.v().transform(this, "gb.cf");
            Aggregator.v().transform(this, "gb.asv2", "only-stack-locals");
            UnusedLocalEliminator.v().transform(this, "gb.ule");
        }    
    }

//      void print_debug(java.io.PrintWriter out)
//      {
//          StmtList stmtList = this.getUnits();

        
//          Map stmtToName = new HashMap(stmtList.size() * 2 + 1, 0.7f);
//  /*
//          StmtGraph stmtGraph = new BriefStmtGraph(stmtList);
//  */
//          /*
//          System.out.println("Constructing LocalDefs of " + this.getMethod().getName() + "...");

//          LocalDefs localDefs = new LocalDefs(graphBody);

//          System.out.println("Constructing LocalUses of " + getName() + "...");

//          LocalUses localUses = new LocalUses(stmtGraph, localDefs);

//          LocalCopies localCopies = new LocalCopies(stmtGraph);

//          System.out.println("Constructing LiveLocals of " + this.getMethod().getName() + " ...");
//          LiveLocals liveLocals = new LiveLocals(stmtGraph);
//          */

//          // Create statement name table
//          {
//             int labelCount = 0;

//              Iterator stmtIt = stmtList.iterator();

//              while(stmtIt.hasNext())
//              {
//                  Stmt s = (Stmt) stmtIt.next();

//                  stmtToName.put(s, new Integer(labelCount++).toString());
//              }
//          }

//          for(int j = 0; j < stmtList.size(); j++)
//          {
//              Stmt s = ((Stmt) stmtList.get(j));

//              out.print("    " + stmtToName.get(s) + ": ");

//              out.print(s.toString(stmtToName, "        "));
//              out.print(";");
//          /*

//              // Print info about live locals
//              {
//                  Iterator localIt = liveLocals.getLiveLocalsAfter(s).iterator();

//                  out.print("   [");

//                  while(localIt.hasNext())
//                  {
//                      out.print(localIt.next());

//                      if(localIt.hasNext())
//                          out.print(", ");

//                  }

//                  out.print("]");
//              }
//          */


//               /*
//               // Print info about uses
//                  if(s instanceof DefinitionStmt)
//                  {
//                      Iterator useIt = localUses.getUsesOf((DefinitionStmt) s).iterator();

//                      out.print("   (");

//                      while(useIt.hasNext())
//                      {
//                          if(k != 0)
//                              out.print(", ");

//                          out.print(stmtToName.get(useIt.next()));
//                      }

//                      out.print(")");
//                  }
//              */

//  /*
//              // Print info about defs
//              {
//                  Iterator boxIt = s.getUseBoxes().iterator();

//                  while(boxIt.hasNext())
//                  {
//                      ValueBox useBox = (ValueBox) boxIt.next();

//                      if(useBox.getValue() instanceof Local)
//                      {
//                          Iterator defIt = localDefs.getDefsOfAt((Local) useBox.getValue(), s).iterator();

//                          out.print("  " + useBox.getValue() + " = {");

//                          while(defIt.hasNext())
//                          {
//                              out.print(stmtToName.get((Stmt) defIt.next()));

//                              if(defIt.hasNext())
//                                  out.print(", ");
//                          }

//                          out.print("}");
//                      }
//                  }
//              } */
//              /*
//              // Print info about successors
//              {
//                  Iterator succIt = stmtGraph.getSuccsOf(s).iterator();

//                  out.print("    [");

//                  if(succIt.hasNext())
//                  {
//                      out.print(stmtToName.get(succIt.next()));

//                      while(succIt.hasNext())
//                      {
//                          Stmt stmt = (Stmt) succIt.next();

//                          out.print(", " + stmtToName.get(stmt));
//                      }
//                  }

//                  out.print("]");
//              }
//                  */
//              /*
//              // Print info about predecessors
//              {
//                  Stmt[] preds = stmtGraph.getPredsOf(s);

//                  out.print("    {");

//                  for(int k = 0; k < preds.length; k++)
//                  {
//                      if(k != 0)
//                          out.print(", ");

//                      out.print(stmtToName.get(preds[k]));
//                  }

//                  out.print("}");
//              }
//              */
//              out.println();
//          }

//          // Print out exceptions
//          {
//              Iterator trapIt = this.getTraps().iterator();

//              while(trapIt.hasNext())
//              {
//                  Trap trap = (Trap) trapIt.next();

//                  out.println(".catch " + trap.getException().getName() + " from " +
//                      stmtToName.get(trap.getBeginUnit()) + " to " + stmtToName.get(trap.getEndUnit()) +
//                      " with " + stmtToName.get(trap.getHandlerUnit()));
//              }
//          }
//      }
}
