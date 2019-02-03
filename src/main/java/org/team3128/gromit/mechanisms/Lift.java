package org.team3128.gromit.mechanisms;

import org.team3128.common.util.Constants;
import org.team3128.common.util.Log;
import org.team3128.common.util.units.Length;

//import org.team3128.gromit.mechanisms.Intake.IntakeState;

import com.ctre.phoenix.motorcontrol.ControlMode;
import com.ctre.phoenix.motorcontrol.can.TalonSRX;

import edu.wpi.first.wpilibj.DigitalInput;
import edu.wpi.first.wpilibj.command.Command;

/**
 * Control system for the two-stage, continuous elevator-style lift mechanism. The
 * {@link FourBar} is mounted to the first stage of the list.
 * 
 * @author Chris, Jude, Tygan
 * 
 */

public class Lift
{
	/**
	 * The ratio of the number of native units that results in a forkift
	 * movement of 1 centimeter.
	 * 
	 * MAX ENCODER POSITION = 20,000 MAX HEIGHT = 12ft
	 */
	public final double ratio = 19100.0 / (76 * Length.in); //to be calculated
	final double tolerance = 5 * Length.in; //to be calculated

	public double error, currentPosition;

	public enum LiftHeight
	{
		/*
		these need to be deifined better
		*/
		BASE(0 * Length.ft),

		INTAKE_FLOOR_CARGO(1.5 * Length.ft), //first raise for ball intake
		HOLD_CARGO(2.5 * Length.ft), //second raise for ball intake

		LOW_CARGO(3 * Length.ft),
		MID_CARGO(59 * Length.in),
		TOP_CARGO(64 * Length.in),
		
        LOW_HATCH(0 * Length.ft), //same for rocket and cargo and loading station
        MID_HATCH(0 * Length.ft),
		TOP_HATCH(0 * Length.ft),
		
        INTAKE_LOADING_CARGO(0 * Length.ft);

		public double targetHeight;

		private LiftHeight(double height)
		{
			this.targetHeight = height;
		}
	}

	public enum LiftControlMode
	{
		PERCENT(2, "Percent Output"),
		POSITION_UP(0, "Position (Up)"),
		POSITION_DOWN(1, "Position (Down)");

		private int pidSlot;
		private String name;

		private LiftControlMode(int pidSlot, String name)
		{
			this.pidSlot = pidSlot;
			this.name = name;
		}

		public int getPIDSlot()
		{
			return pidSlot;
		}

		public String getName()
		{
			return name;
		}

	}

	public void setControlMode(LiftControlMode mode)
	{
		if (mode != controlMode)
		{
			controlMode = mode;
			Log.debug("Lift", "Setting control mode to " + mode.getName() + ", with PID slot " + mode.getPIDSlot());
			liftMotor.selectProfileSlot(mode.getPIDSlot(), 0);
		}
	}

	TalonSRX liftMotor;
	DigitalInput softStopLimitSwitch;
	Thread depositCubeThread, liftThread;

	public LiftControlMode controlMode;
	public LiftHeight state;

	int limitSwitchLocation, LiftMaxVelocity;

	public double brakePower = 0.15;
	public double brakeHeight = 0.5 * Length.ft;

	public double maxHeight;

	public boolean disabled = false;

	public boolean canLower = false;
	public boolean canRaise = true;

	private double desiredTarget = 0;
	private double setPoint = 0;

	public boolean override = false;


	private static Lift instance = null;
	public static Lift getInstance() {
		if (instance != null) {
			return instance;
		}

		Log.fatal("Lift", "Attempted to get instance before initializtion! Call initialize(...) first.");
		return null;
	}

	public static void initialize(LiftHeight state, TalonSRX liftMotor, DigitalInput softStopLimitSwitch, int limitSwitchLocation, int LiftMaxVelocity) {
		instance = new Lift(state, liftMotor, softStopLimitSwitch, limitSwitchLocation, LiftMaxVelocity);
	}

	private Lift(LiftHeight state, TalonSRX liftMotor, DigitalInput softStopLimitSwitch, int limitSwitchLocation, int LiftMaxVelocity) {
		this.liftMotor = liftMotor;
		this.softStopLimitSwitch = softStopLimitSwitch;
		this.limitSwitchLocation = limitSwitchLocation;
		this.state = state;

		controlMode = LiftControlMode.PERCENT;

		liftMotor.configMotionCruiseVelocity(2000, Constants.CAN_TIMEOUT);
		liftMotor.configMotionAcceleration(4000, Constants.CAN_TIMEOUT);

		liftMotor.selectProfileSlot(0, 0);

		liftMotor.configOpenloopRamp(0.2, Constants.CAN_TIMEOUT);

		// We may want to nuke this entire thread because we'll just give drivers complete override.
		liftThread = new Thread(() ->
		{
			double target = 0;

			while (true)
			{
				if (this.getLiftSwitch())
				{
					this.liftMotor.setSelectedSensorPosition(limitSwitchLocation, 0, Constants.CAN_TIMEOUT);
				}

				if (this.disabled)
				{
					this.liftMotor.set(ControlMode.PercentOutput, 0);
				}
				else {
					target = 0;

					this.canRaise = this.liftMotor.getSelectedSensorPosition(0) < this.maxHeight;
					this.canLower = this.liftMotor.getSelectedSensorPosition(0) > 100;

					if (this.controlMode == LiftControlMode.PERCENT) {
						if (this.override) {
							target = this.desiredTarget;
							this.liftMotor.set(ControlMode.PercentOutput, target);
						}
						else {
							if (this.desiredTarget > 0 && this.canRaise) {
								target = this.desiredTarget;
							}
							else if (this.desiredTarget < 0 && this.canLower) {
								target = 0.7 * this.desiredTarget;
							}

							if ((Math.abs(target) < 0.1
									&& this.liftMotor.getSelectedSensorPosition(0) / ratio >= this.brakeHeight)) {
								target = this.brakePower;
							}

							if (Math.abs(target - this.setPoint) > 0.0001) {
								this.liftMotor.set(ControlMode.PercentOutput, target);

								this.setPoint = target;
							}
						}
					}
				}

				this.currentPosition = liftMotor.getSelectedSensorPosition(0) / ratio;
				double targetHeight = this.state.targetHeight;

				this.error = Math.abs(currentPosition - targetHeight);

				try
				{
					Thread.sleep(100);
				}
				catch (InterruptedException e)
				{
					e.printStackTrace();
				}

			}
		});

		liftThread.start();
	}

	public void setState(LiftHeight liftState)
	{
		if (state != liftState)
		{
			if (liftState.targetHeight < state.targetHeight)
			{
				setControlMode(LiftControlMode.POSITION_DOWN);
			}
			else
			{
				setControlMode(LiftControlMode.POSITION_UP);
			}
			state = liftState;
			Log.info("Lift", "Going to " + state.targetHeight + " cm.");
			liftMotor.set(ControlMode.MotionMagic, state.targetHeight * ratio);
		}
	}

	public void powerControl(double joystick)
	{
		setControlMode(LiftControlMode.PERCENT);

		desiredTarget = joystick;
	}

	public boolean getLiftSwitch()
	{
		return !softStopLimitSwitch.get();
	}

	public class CmdForceZeroLift extends Command {
		private boolean done = false;

		public CmdForceZeroLift() {
			super(0.5);
		}

		@Override
		protected void initialize() {
			liftMotor.setSelectedSensorPosition(limitSwitchLocation, 0, Constants.CAN_TIMEOUT);
			state = LiftHeight.BASE;

			done = true;
		}

		@Override
		protected boolean isFinished()
		{
			return done || isTimedOut();
		}
	}

	public class CmdSetLiftPosition extends Command
	{
		LiftHeight heightState;

		public CmdSetLiftPosition(LiftHeight heightState)
		{
			super(3);
			this.heightState = heightState;
		}

		@Override
		protected void initialize()
		{
			setState(heightState);
			Log.debug("CmdSetLiftPosition", "Changing state to " + heightState.name());
			Log.debug("CmdSetLiftPosition", "Target: " + liftMotor.getClosedLoopTarget(0));
		}


		@Override
		protected void execute() {
			Log.debug("CmdSetLiftPosition", "Error: " + (liftMotor.getSelectedSensorPosition(0) - (int)(heightState.targetHeight * ratio)));
		}

		@Override
		protected void end() {
			powerControl(0);
			Log.debug("CmdSetLiftPosition", "Lift at desired height of " + heightState.targetHeight);
		}

		@Override
		protected void interrupted()
		{
			Log.debug("Lift and Intake", "Ending, was interrupted.");
			end();
		}

		@Override
		protected boolean isFinished()
		{
			return isTimedOut() || Math.abs(liftMotor.getSelectedSensorPosition(0) - (int)(heightState.targetHeight * ratio)) < 300;
		}
	}
}