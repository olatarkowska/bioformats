//
// CaptureHandler.java
//

/*
VisBio application for visualization of multidimensional
biological image data. Copyright (C) 2002-2004 Curtis Rueden.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.visbio.view;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;

import ij.io.FileSaver;

import java.awt.Image;

import java.io.IOException;
import java.io.File;

import java.rmi.RemoteException;

import java.util.Vector;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

import loci.visbio.SystemManager;
import loci.visbio.WindowManager;

import loci.visbio.state.OptionManager;

import visad.*;

import visad.data.avi.AVIForm;

import visad.util.DataUtility;
import visad.util.ExtensionFileFilter;
import visad.util.Util;

/** Provides logic for capturing display screenshots and movies. */
public class CaptureHandler {

  // -- Fields --

  /** Associated display dialog. */
  protected DisplayDialog dialog;

  /** GUI controls for capture handler. */
  protected CapturePanel panel;

  /** File chooser for snapshot output. */
  protected JFileChooser imageBox;

  /** File chooser for movie output. */
  protected JFileChooser movieBox;


  // -- Constructor --

  /** Creates a display capture handler. */
  public CaptureHandler(DisplayDialog dd) { dialog = dd; }


  // -- CaptureHandler API methods --

  /** Gets associated display dialog. */
  public DisplayDialog getDialog() { return dialog; }

  /** Gets GUI controls for this capture handler. */
  public CapturePanel getPanel() { return panel; }

  /** Gets a snapshot of the display. */
  public Image getSnapshot() { return dialog.getDisplay().getImage(); }

  /** Saves a snapshot of the display to a file specified by the user. */
  public void saveSnapshot() {
    int rval = imageBox.showSaveDialog(dialog);
    if (rval != JFileChooser.APPROVE_OPTION) return;

    // determine file type
    final String file = imageBox.getSelectedFile().getPath();
    String ext = "";
    int dot = file.lastIndexOf(".");
    if (dot >= 0) ext = file.substring(dot + 1).toLowerCase();
    final boolean tiff = ext.equals("tif") || ext.equals("tiff");
    final boolean jpeg = ext.equals("jpg") || ext.equals("jpeg");
    final boolean raw = ext.equals("raw");
    if (!tiff && !jpeg && !raw) {
      JOptionPane.showMessageDialog(dialog, "Invalid filename (" +
        file + "): extension must be TIFF, JPEG or RAW.",
        "Cannot export snapshot", JOptionPane.ERROR_MESSAGE);
      return;
    }

    // save file in a separate thread
    Thread t = new Thread(new Runnable() {
      public void run() {
        FileSaver saver = new FileSaver(new ImagePlus("null", getSnapshot()));
        if (tiff) saver.saveAsTiff(file);
        else if (jpeg) saver.saveAsJpeg(file);
        else if (raw) saver.saveAsRaw(file);
      }
    });
    t.start();
  }

  /** Sends a snapshot of the display to ImageJ. */
  public void sendToImageJ() {
    Thread t = new Thread(new Runnable() {
      public void run() {
        ImageJ ij = IJ.getInstance();
        if (ij == null || (ij != null && !ij.isShowing())) {
          // create new ImageJ instance
          File dir = new File(System.getProperty("user.dir"));
          File newDir = new File(dir.getParentFile().getParentFile(), "ij");
          System.setProperty("user.dir", newDir.getPath());
          new ImageJ(null);
          System.setProperty("user.dir", dir.getPath());

          // display ImageJ warning
          OptionManager om = (OptionManager)
            dialog.getVisBio().getManager(OptionManager.class);
          om.checkWarning(DisplayManager.WARN_IMAGEJ, false,
            "Quitting ImageJ will also shut down VisBio, with no\n" +
            "warning or opportunity to save your work. Similarly,\n" +
            "quitting VisBio will shut down ImageJ without warning.\n" +
            "Please remember to save your work in both programs\n" +
            "before closing either one.");
        }
        new ImagePlus("VisBio snapshot", getSnapshot()).show();
      }
    });
    t.start();
  }

  /** Creates a movie of the given transformation sequence. */
  public void captureMovie(Vector positions, double secPerTrans,
    int framesPerSec, boolean sine, boolean movie)
  {
    final int size = positions.size();
    if (size < 1) {
      JOptionPane.showMessageDialog(dialog, "Must have at least two display " +
        "positions on the list.", "Cannot record movie",
        JOptionPane.ERROR_MESSAGE);
      return;
    }

    final DisplayImpl d = dialog.getDisplay();
    if (d == null) {
      JOptionPane.showMessageDialog(dialog, "Display not found.",
        "Cannot record movie", JOptionPane.ERROR_MESSAGE);
      return;
    }
    final ProjectionControl pc = d.getProjectionControl();

    final int fps = framesPerSec;
    final int framesPerTrans = (int) (framesPerSec * secPerTrans);
    final int total = (size - 1) * framesPerTrans + 1;

    // get output filename(s) from the user
    String file = null;
    int dot = -1;
    boolean tiff = false, jpeg = false, raw = false;
    if (movie) {
      int rval = movieBox.showSaveDialog(dialog);
      if (rval != JFileChooser.APPROVE_OPTION) return;
      file = movieBox.getSelectedFile().getPath();
      if (file.indexOf(".") < 0) file = file + ".avi";
    }
    else {
      int rval = imageBox.showSaveDialog(dialog);
      if (rval != JFileChooser.APPROVE_OPTION) return;
      file = imageBox.getSelectedFile().getPath();
      String ext = "";
      dot = file.lastIndexOf(".");
      if (dot >= 0) ext = file.substring(dot + 1).toLowerCase();
      tiff = ext.equals("tif") || ext.equals("tiff");
      jpeg = ext.equals("jpg") || ext.equals("jpeg");
      raw = ext.equals("raw");
      if (!tiff && !jpeg && !raw) {
        JOptionPane.showMessageDialog(dialog, "Invalid filename (" +
          file + "): extension must be TIFF, JPEG or RAW.",
          "Cannot create image sequence", JOptionPane.ERROR_MESSAGE);
        return;
      }
    }

    // capture image sequence in a separate thread
    final boolean aviMovie = movie;
    final String filename = file;
    final int dotIndex = dot;
    final boolean isTiff = tiff, isJpeg = jpeg, isRaw = raw;
    final Vector pos = positions;
    final int frm = framesPerTrans;
    final boolean doSine = sine;

    Thread t = new Thread(new Runnable() {
      public void run() {
        WindowManager wm = (WindowManager)
          dialog.getVisBio().getManager(WindowManager.class);
        wm.setWaitCursor(true);

        // step incremental from position to position, grabbing images
        double[] mxStart = (double[]) pos.elementAt(0);
        Image[] images = new Image[total];
        int count = 0;
        for (int i=1; i<size; i++) {
          double[] mxEnd = (double[]) pos.elementAt(i);
          double[] mx = new double[mxStart.length];
          for (int j=0; j<frm; j++) {
            setProgress(100 * count / total,
              "Capturing image " + (count + 1) + "/" + total);
            double p = (double) j / frm;
            if (doSine) p = sine(p);
            for (int k=0; k<mx.length; k++) {
              mx[k] = p * (mxEnd[k] - mxStart[k]) + mxStart[k];
            }
            images[count++] = captureImage(pc, mx, d);
          }
          mxStart = mxEnd;
        }

        // cap off last frame
        setProgress(100, "Capturing image " + total + "/" + total);
        images[count] = captureImage(pc, mxStart, d);

        // save movie data
        if (aviMovie) {
          try {
            // convert image frames into VisAD data objects
            FlatField[] ff = new FlatField[total];
            for (int i=0; i<total; i++) {
              setProgress(100 * i / total,
                "Processing image " + (i + 1) + "/" + total);
              ff[i] = DataUtility.makeField(images[i]);
            }
            setProgress(100, "Saving movie");

            // compile frames into a single data object
            RealType index = RealType.getRealType("index");
            FunctionType ftype = new FunctionType(index, ff[0].getType());
            Integer1DSet fset = new Integer1DSet(total);
            FieldImpl field = new FieldImpl(ftype, fset);
            field.setSamples(ff, false);

            // write data out to AVI file
            AVIForm saver = new AVIForm();
            saver.setFrameRate(fps);
            saver.save(filename, field, true);
          }
          catch (VisADException exc) { exc.printStackTrace(); }
          catch (RemoteException exc) { exc.printStackTrace(); }
          catch (IOException exc) { exc.printStackTrace(); }
        }
        else {
          for (int i=0; i<total; i++) {
            String num = "" + (i + 1);
            int len = ("" + total).length();
            while (num.length() < len) num = "0" + num;
            String s = filename.substring(0, dotIndex) +
              num + filename.substring(dotIndex);
            setProgress(100 * i / total, "Saving " +
              new File(s).getName() + " (" + (i + 1) + "/" + total + ")");
            FileSaver saver = new FileSaver(new ImagePlus("null", images[i]));
            if (isTiff) saver.saveAsTiff(s);
            else if (isJpeg) saver.saveAsJpeg(s);
            else if (isRaw) saver.saveAsRaw(s);
          }
        }

        // clean up
        setProgress(100, "Finishing up");
        images = null;
        SystemManager.gc();

        setProgress(0, "");
        wm.setWaitCursor(false);
      }
    });
    t.start();
  }


  // -- CaptureHandler API methods - state logic --

  /** Writes the current state. */
  public void saveState() {
    // CTR START HERE - panel.getCaptureDialog(), extract settings from there
    // CTR TODO CaptureHandler saveState
    /*
    dialog.setAttr("brightness", "" + brightness);
    dialog.setAttr("contrast", "" + contrast);
    dialog.setAttr("colorModel", "" + model);
    dialog.setAttr("red", red == null ? "null" : red.getName());
    dialog.setAttr("green", green == null ? "null" : green.getName());
    dialog.setAttr("blue", blue == null ? "null" : blue.getName());
    dialog.setAttr("colorMin", ObjectUtil.arrayToString(getLo()));
    dialog.setAttr("colorMax", ObjectUtil.arrayToString(getHi()));
    dialog.setAttr("colorFixed", ObjectUtil.arrayToString(getFixed()));

    float[][][] tables = getTables();
    if (tables == null) {
      dialog.setAttr("tables", "null");
    }
    else {
      dialog.setAttr("tables", "" + tables.length);
      for (int i=0; i<tables.length; i++) {
        if (tables[i] == null) dialog.setAttr("table" + i, "null");
        else {
          dialog.setAttr("table" + i, "" + tables[i].length);
          for (int j=0; j<tables[i].length; j++) {
            if (tables[i][j] == null) {
              dialog.setAttr("table" + i + "-" + j, "null");
            }
            else {
              dialog.setAttr("table" + i + "-" + j,
                ObjectUtil.arrayToString(tables[i][j]));
            }
          }
        }
      }
    }
    */
  }

  /** Restores the current state. */
  public void restoreState() {
    // CTR TODO CaptureHandler restoreState
  }

  /** Tests whether two objects are in equivalent states. */
  public boolean matches(CaptureHandler handler) {
    // CTR TODO CaptureHandler matches
    return false;
  }

  /**
   * Modifies this object's state to match that of the given object.
   * If the argument is null, the object is initialized according to
   * its current state instead.
   */ 
  public void initState(CaptureHandler handler) {
    // CTR TODO CaptureHandler initState
    if (handler != null) {
      //this.thing = that.thing...
    }

    if (panel == null) {
      panel = new CapturePanel(this);

      // snapshot file chooser
      imageBox = new JFileChooser();
      imageBox.addChoosableFileFilter(new ExtensionFileFilter(
        new String[] {"jpg", "jpeg"}, "JPEG files"));
      imageBox.addChoosableFileFilter(new ExtensionFileFilter(
        "raw", "RAW files"));
      imageBox.addChoosableFileFilter(new ExtensionFileFilter(
        new String[] {"tif", "tiff"}, "TIFF files"));

      // movie file chooser
      movieBox = new JFileChooser();
      movieBox.addChoosableFileFilter(new ExtensionFileFilter(
        new String[] {"avi"}, "AVI movies"));
    }
  }


  // -- Helper methods --

  /**
   * Takes a snapshot of the given display
   * with the specified projection matrix.
   */
  protected Image captureImage(ProjectionControl pc,
    double[] mx, DisplayImpl d)
  {
    Image image = null;
    try {
      pc.setMatrix(mx);

      // HACK - lame, stupid waiting trick to capture images properly
      try { Thread.sleep(100); }
      catch (InterruptedException exc) { exc.printStackTrace(); }

      image = d.getImage(false);
    }
    catch (VisADException exc) { exc.printStackTrace(); }
    catch (RemoteException exc) { exc.printStackTrace(); }
    catch (IOException exc) { exc.printStackTrace(); }
    return image;
  }

  /** Sets capture panel's progress bar percentage value and message. */
  protected void setProgress(int percent, String message) {
    final int value = percent;
    final String msg = message;
    Util.invoke(false, new Runnable() {
      public void run() {
        CaptureDialog dialog = panel.getCaptureDialog();
        dialog.setProgressValue(value);
        if (msg != null) dialog.setProgressMessage(msg);
      }
    });
  }


  // -- Utility methods --

  /** Evaluates a smooth sine function at the given value. */
  protected static double sine(double x) {
    // [0, 1] -> [-pi/2, pi/2] -> [0, 1]
    return (Math.sin(Math.PI * (x - 0.5)) + 1) / 2;
  }

}
